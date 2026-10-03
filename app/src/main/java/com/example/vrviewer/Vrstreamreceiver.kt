package com.example.vrviewer

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import android.view.Surface
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class VrStreamReceiver(
    private val pcIp:     String,
    private val surface:  Surface,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG              = "VrStreamReceiver"
        private const val STREAM_PORT      = 47295
        private const val MIME             = "video/avc"
        private const val WIDTH            = 1280
        private const val HEIGHT           = 720
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 5_000

        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8

        private val ANNEX_B_START = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }

    private val running  = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private var future: Future<*>? = null

    @Volatile private var activeSocket: Socket? = null

    fun start() {
        if (running.getAndSet(true)) return
        future = executor.submit { receiveLoop() }
    }

    fun stop() {
        running.set(false)
        try { activeSocket?.close() } catch (_: Exception) {}
        future?.cancel(false)
    }

    private fun receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        var codec: MediaCodec? = null

        while (running.get()) {
            var socket: Socket? = null
            try {
                onStatus("Conectando al stream…")
                socket = Socket()
                activeSocket = socket
                socket.connect(InetSocketAddress(pcIp, STREAM_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout         = CONNECT_TIMEOUT_MS
                socket.tcpNoDelay        = true
                socket.receiveBufferSize = 256 * 1024
                val input = socket.getInputStream()
                onStatus("Stream conectado ✓")

                codec?.let { try { it.stop(); it.release() } catch (_: Exception) {} }

                if (!surface.isValid) {
                    onStatus("Surface no disponible — esperando…")
                    Thread.sleep(500)
                    continue
                }

                codec = createDecoder()
                codec.start()

                receiveFrames(input, codec)

            } catch (e: Exception) {
                Log.e(TAG, "Error de stream: ${e.message}")
                if (running.get()) {
                    onStatus("Stream desconectado — reconectando…")
                    Thread.sleep(RECONNECT_DELAY_MS)
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                activeSocket = null
            }
        }

        codec?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        onStatus("Stream detenido")
    }

    private fun createDecoder(): MediaCodec {
        val codec = MediaCodec.createDecoderByType(MIME)
        val fmt = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            setInteger(MediaFormat.KEY_MAX_WIDTH, 1920)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, 1080)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, 32767)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            setInteger("low-latency", 1)
        }
        codec.configure(fmt, surface, null, 0)
        return codec
    }

    private fun ensureAnnexB(pkt: ByteArray): ByteArray {
        if (pkt.size < 4) return pkt

        if (pkt[0] == 0x00.toByte() && pkt[1] == 0x00.toByte() &&
            pkt[2] == 0x00.toByte() && pkt[3] == 0x01.toByte()) {
            return pkt
        }

        if (pkt[0] == 0x00.toByte() && pkt[1] == 0x00.toByte() &&
            pkt[2] == 0x01.toByte()) {
            val out = ByteArray(pkt.size + 1)
            out[0] = 0x00
            System.arraycopy(pkt, 0, out, 1, pkt.size)
            return out
        }

        val possibleLen = ((pkt[0].toInt() and 0xFF) shl 24) or
                ((pkt[1].toInt() and 0xFF) shl 16) or
                ((pkt[2].toInt() and 0xFF) shl  8) or
                (pkt[3].toInt() and 0xFF)

        if (possibleLen > 0 && possibleLen <= pkt.size - 4) {
            val out = ByteArray(pkt.size)
            var offset = 0
            var outOffset = 0
            var valid = true
            while (offset + 4 <= pkt.size) {
                val nalLen = ((pkt[offset].toInt() and 0xFF) shl 24) or
                        ((pkt[offset+1].toInt() and 0xFF) shl 16) or
                        ((pkt[offset+2].toInt() and 0xFF) shl  8) or
                        (pkt[offset+3].toInt() and 0xFF)
                offset += 4
                if (nalLen <= 0 || offset + nalLen > pkt.size) {
                    valid = false
                    break
                }
                out[outOffset] = 0x00
                out[outOffset + 1] = 0x00
                out[outOffset + 2] = 0x00
                out[outOffset + 3] = 0x01
                outOffset += 4
                System.arraycopy(pkt, offset, out, outOffset, nalLen)
                outOffset += nalLen
                offset += nalLen
            }
            if (valid && outOffset == pkt.size) {
                return out
            } else if (valid && outOffset > 0) {
                return out.copyOf(outOffset)
            }
        }

        val out = ByteArray(pkt.size + 4)
        out[0] = 0x00; out[1] = 0x00; out[2] = 0x00; out[3] = 0x01
        System.arraycopy(pkt, 0, out, 4, pkt.size)
        return out
    }

    private fun receiveFrames(input: InputStream, codec: MediaCodec) {
        val lenBuf = ByteArray(4)
        val info   = MediaCodec.BufferInfo()
        var frameCount = 0

        while (running.get()) {
            readFully(input, lenBuf, 4)

            val totalLen = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                    ((lenBuf[1].toInt() and 0xFF) shl 16) or
                    ((lenBuf[2].toInt() and 0xFF) shl  8) or
                    (lenBuf[3].toInt() and 0xFF)

            if (totalLen <= 0 || totalLen > 8 * 1024 * 1024) {
                Log.w(TAG, "Longitud inválida: $totalLen")
                throw RuntimeException("Longitud de paquete inválida: $totalLen")
            }

            val rawPkt = ByteArray(totalLen)
            readFully(input, rawPkt, totalLen)

            if (frameCount < 10) {
                val header = rawPkt.take(minOf(8, totalLen))
                    .joinToString(" ") { "%02X".format(it) }
                Log.d(TAG, "Frame #$frameCount raw header [$totalLen bytes]: $header")
            }

            val pkt = ensureAnnexB(rawPkt)

            val nalType = if (pkt.size > 4) (pkt[4].toInt() and 0x1F) else 0
            val isParamSet = (nalType == NAL_TYPE_SPS || nalType == NAL_TYPE_PPS)

            var inIdx = codec.dequeueInputBuffer(if (isParamSet) 10_000L else 0L)

            if (inIdx < 0 && isParamSet) {
                Log.w(TAG, "Sin buffer para paquete de config (nalType=$nalType) — reintentando con más margen")
                inIdx = codec.dequeueInputBuffer(100_000L)
                if (inIdx < 0) {
                    Log.e(TAG, "No se pudo obtener buffer para SPS/PPS tras reintento — forzando reconexión")
                    throw RuntimeException("No se pudo entregar paquete de configuración (nalType=$nalType) al decoder")
                }
            }

            if (inIdx < 0) {
                var outIdx = codec.dequeueOutputBuffer(info, 0L)
                while (outIdx >= 0) {
                    codec.releaseOutputBuffer(outIdx, true)
                    outIdx = codec.dequeueOutputBuffer(info, 0L)
                }
                inIdx = codec.dequeueInputBuffer(1_000L)
            }

            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx)!!
                buf.clear()
                buf.put(pkt)

                val pts   = System.nanoTime() / 1000L
                val flags = 0
                codec.queueInputBuffer(inIdx, 0, pkt.size, pts, flags)
                frameCount++

                if (frameCount <= 10 || frameCount % 100 == 0) {
                    Log.d(TAG, "Frame #$frameCount nalType=$nalType len=${pkt.size} isParamSet=$isParamSet")
                }
            } else {
                Log.d(TAG, "Sin input buffer (frame #$frameCount nalType=$nalType) — descartado")
            }

            var outIdx = codec.dequeueOutputBuffer(info, 0L)
            while (outIdx >= 0) {
                codec.releaseOutputBuffer(outIdx, true)
                outIdx = codec.dequeueOutputBuffer(info, 0L)
            }
            when (outIdx) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    Log.d(TAG, "Decoder: formato de salida cambiado")
                MediaCodec.INFO_TRY_AGAIN_LATER -> { /* normal */ }
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var offset = 0
        while (offset < len) {
            val n = input.read(buf, offset, len - offset)
            if (n < 0) throw RuntimeException("EOF inesperado (offset=$offset len=$len)")
            offset += n
        }
    }
}
