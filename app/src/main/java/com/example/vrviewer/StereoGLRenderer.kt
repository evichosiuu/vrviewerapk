package com.example.vrviewer

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class StereoGLRenderer(
    private val onSurfaceReady: (Surface) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile var distortionK: Float = 0.22f
    @Volatile var lensSeparation: Float = 0.0f
    @Volatile var lensZoom: Float = 1.0f

    // ── SBS Video properties ──
    @Volatile var videoFullscreen: Boolean = false
        private set
    @Volatile var videoEnvMode: Boolean = false
        private set
    @Volatile var videoWindowScale: Float = 1.0f
        private set

    @Volatile private var isMonoSource: Boolean = false
    @Volatile private var isVideoWorldLocked: Boolean = false
    @Volatile private var videoOffsetX: Float = 0f
    @Volatile private var videoOffsetY: Float = 0f
    @Volatile private var headYaw: Float = 0f
    @Volatile private var headPitch: Float = 0f
    @Volatile private var headRoll: Float = 0f
    @Volatile private var videoAspect: Float = 16f / 9f

    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    @Volatile private var frameAvailable = false
    private val stMatrix = FloatArray(16)

    private var program = 0
    private var aPosLoc = 0
    private var uTexLoc = 0
    private var uStMatrixLoc = 0
    private var uKLoc = 0
    private var uEyeLoc = 0
    private var uSepLoc = 0
    private var uZoomLoc = 0

    private var windowTextureId = 0
    private var uWindowTexLoc = 0
    private var uWinVisibleLoc = 0
    private var uWinXLoc = 0
    private var uWinYLoc = 0
    private var uWinHalfWLoc = 0
    private var uWinHalfHLoc = 0
    private var uWinParallaxLoc = 0

    @Volatile private var pendingWindowBitmap: Bitmap? = null
    @Volatile private var pendingWindowRelease: (() -> Unit)? = null
    @Volatile private var windowBitmapDirty = false

    @Volatile var windowVisible: Boolean = false
        private set
    @Volatile private var windowX = 0f
    @Volatile private var windowY = 0f
    @Volatile private var windowHalfW = BASE_HALF_W
    @Volatile private var windowHalfH = BASE_HALF_H
    @Volatile private var windowParallax = 0.05f

    private var bgTextureId = 0
    @Volatile private var pendingBgBitmap: Bitmap? = null
    @Volatile private var bgBitmapDirty = false

    private var hubCamTextureId = 0
    private var uHubCamTexLoc = 0
    private var uHubCamActiveLoc = 0
    @Volatile private var pendingHubCamBitmap: Bitmap? = null
    @Volatile private var hubCamBitmapDirty = false
    @Volatile var hubCameraActive: Boolean = false
        private set

    private var viewW = 0
    private var viewH = 0

    private val quadVerts = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f,  1f,
        1f,  1f
    )
    private lateinit var vertexBuffer: FloatBuffer

    companion object {
        private const val BASE_HALF_W = 0.34f
        private const val BASE_HALF_H = 0.2125f

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vScreenPos;
            void main() {
                vScreenPos = aPosition;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 vScreenPos;
    uniform samplerExternalOES uTexture;
    uniform sampler2D uWindowTex;
    uniform mat4 uStMatrix;
    uniform float uK;
    uniform float uEye;
    uniform float uSep;
    uniform float uZoom;
    uniform float uWinVisible;
    uniform float uWinX;
    uniform float uWinY;
    uniform float uWinHalfW;
    uniform float uWinHalfH;
    uniform float uWinParallax;
    uniform sampler2D uHubCamTex;
    uniform float uHubCamActive;

    void main() {
        float centerShift = (uEye < 0.5) ? -uSep : uSep;
        vec2 pos = vScreenPos;
        pos.x -= centerShift;

        vec4 color = vec4(0.0, 0.0, 0.0, 1.0);
        bool inWindow = false;

        // ── 1) VENTANA FLOTANTE DEL MENÚ HUB ──
        if (uWinVisible > 0.5) {
            float eyeSign = (uEye < 0.5) ? -1.0 : 1.0;
            float wx = pos.x - (uWinX + eyeSign * uWinParallax);
            float wy = pos.y - uWinY;
            if (abs(wx) < uWinHalfW && abs(wy) < uWinHalfH) {
                vec2 winUv = vec2(
                    (wx / uWinHalfW) * 0.5 + 0.5,
                    0.5 - (wy / uWinHalfH) * 0.5
                );
                color = texture2D(uWindowTex, winUv);
                inWindow = true;
            }
        }

        if (!inWindow) {
            // ── 2) DISTORSIÓN DE BARRIL ──
            float r2 = pos.x * pos.x + pos.y * pos.y;
            float distFactor = 1.0 + uK * r2 + uK * 0.5 * r2 * r2;
            vec2 distorted = pos * distFactor;
            distorted.x += centerShift;

            if (distorted.x < -1.0 || distorted.x > 1.0 || distorted.y < -1.0 || distorted.y > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }

            // ── 3) LUPA RADIAL & FONDO ──
            float lupaFactor = mix(1.0, uZoom, clamp(r2, 0.0, 1.0));
            vec2 magnified = pos * lupaFactor;
            magnified.x += centerShift;

            vec2 uv01 = (magnified + 1.0) * 0.5;
            float u = (uEye < 0.5) ? (uv01.x * 0.5) : (0.5 + uv01.x * 0.5);
            vec4 texUv = uStMatrix * vec4(u, uv01.y, 0.0, 1.0);
            color = texture2D(uTexture, texUv.xy);

            // ── Corrección de Gamma & Perfil de Color sRGB ──
            // Decodificar el vídeo comprimido (con curva gamma ~2.2) para recuperar colores vibrantes y contraste real en pantallas OLED.
            color.rgb = pow(color.rgb, vec3(2.2));
            // Aumentar ligeramente la saturación si es necesario para evitar colores lavados
            float luminance = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
            color.rgb = mix(vec3(luminance), color.rgb, 1.15);
            // Volver a codificar o recortar adecuadamente
            color.rgb = clamp(pow(color.rgb, vec3(1.0 / 2.2)), 0.0, 1.0);
        }

        // ── 4) OVERLAY DE MANOS Y PUNTERO (SIEMPRE AL FRENTE DE LA PESTAÑA DE GOOGLE) ──
        if (uHubCamActive > 0.5) {
            float normX = clamp((pos.x + 1.0) * 0.5, 0.0, 1.0);
            float normY = clamp((1.0 - pos.y) * 0.5, 0.0, 1.0);
            float overlayU = (uEye < 0.5) ? (normX * 0.5) : (0.5 + normX * 0.5);
            vec4 handOverlay = texture2D(uHubCamTex, vec2(overlayU, normY));
            if (handOverlay.a > 0.01) {
                color = mix(color, handOverlay, handOverlay.a);
            }
        }

        gl_FragColor = color;
    }
"""
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTextureId)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        val surf = Surface(st)
        inputSurface = surf
        onSurfaceReady(surf)

        // Textura 2D para el contenido de la ventana flotante del Menú Hub
        val winTex = IntArray(1)
        GLES20.glGenTextures(1, winTex, 0)
        windowTextureId = winTex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, windowTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val placeholder = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, placeholder, 0)
        placeholder.recycle()

        // Textura 2D para la cámara del Menú Hub
        val hubTex = IntArray(1)
        GLES20.glGenTextures(1, hubTex, 0)
        hubCamTextureId = hubTex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hubCamTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val hubPlaceholder = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, hubPlaceholder, 0)
        hubPlaceholder.recycle()

        // Textura 2D para fondo SBS
        val bgTex = IntArray(1)
        GLES20.glGenTextures(1, bgTex, 0)
        bgTextureId = bgTex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val bgPlaceholder = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bgPlaceholder, 0)
        bgPlaceholder.recycle()

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
        uTexLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uStMatrixLoc = GLES20.glGetUniformLocation(program, "uStMatrix")
        uKLoc = GLES20.glGetUniformLocation(program, "uK")
        uEyeLoc = GLES20.glGetUniformLocation(program, "uEye")
        uSepLoc = GLES20.glGetUniformLocation(program, "uSep")
        uZoomLoc = GLES20.glGetUniformLocation(program, "uZoom")
        uWindowTexLoc  = GLES20.glGetUniformLocation(program, "uWindowTex")
        uWinVisibleLoc = GLES20.glGetUniformLocation(program, "uWinVisible")
        uWinXLoc       = GLES20.glGetUniformLocation(program, "uWinX")
        uWinYLoc       = GLES20.glGetUniformLocation(program, "uWinY")
        uWinHalfWLoc   = GLES20.glGetUniformLocation(program, "uWinHalfW")
        uWinHalfHLoc   = GLES20.glGetUniformLocation(program, "uWinHalfH")
        uWinParallaxLoc = GLES20.glGetUniformLocation(program, "uWinParallax")
        uHubCamTexLoc    = GLES20.glGetUniformLocation(program, "uHubCamTex")
        uHubCamActiveLoc = GLES20.glGetUniformLocation(program, "uHubCamActive")

        val bb = ByteBuffer.allocateDirect(quadVerts.size * 4).order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer().apply { put(quadVerts); position(0) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(this) {
            if (frameAvailable) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(stMatrix)
                frameAvailable = false
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTexLoc, 0)
        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
        GLES20.glUniform1f(uKLoc, distortionK)
        GLES20.glUniform1f(uSepLoc, lensSeparation)
        GLES20.glUniform1f(uZoomLoc, lensZoom.coerceAtLeast(0.01f))

        if (bgBitmapDirty) {
            val bmp = pendingBgBitmap
            if (bmp != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTextureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            }
            bgBitmapDirty = false
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        if (windowBitmapDirty) {
            val bmp = pendingWindowBitmap
            if (bmp != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, windowTextureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                pendingWindowRelease?.invoke()
                pendingWindowRelease = null
            }
            windowBitmapDirty = false
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, windowTextureId)
        }
        GLES20.glUniform1i(uWindowTexLoc, 1)
        GLES20.glUniform1f(uWinVisibleLoc, if (windowVisible) 1f else 0f)
        GLES20.glUniform1f(uWinXLoc, windowX)
        GLES20.glUniform1f(uWinYLoc, windowY)
        GLES20.glUniform1f(uWinHalfWLoc, windowHalfW)
        GLES20.glUniform1f(uWinHalfHLoc, windowHalfH)
        GLES20.glUniform1f(uWinParallaxLoc, windowParallax)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        if (hubCamBitmapDirty) {
            val bmp = pendingHubCamBitmap
            if (bmp != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hubCamTextureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            }
            hubCamBitmapDirty = false
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hubCamTextureId)
        }
        GLES20.glUniform1i(uHubCamTexLoc, 2)
        GLES20.glUniform1f(uHubCamActiveLoc, if (hubCameraActive) 1f else 0f)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val halfW = viewW / 2

        GLES20.glViewport(0, 0, halfW, viewH)
        GLES20.glUniform1f(uEyeLoc, 0.0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glViewport(halfW, 0, viewW - halfW, viewH)
        GLES20.glUniform1f(uEyeLoc, 1.0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosLoc)
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        synchronized(this) { frameAvailable = true }
    }

    fun ensureBufferSize(width: Int, height: Int) {
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    fun setWindowVisible(visible: Boolean) {
        windowVisible = visible
    }

    fun setHubCameraActive(active: Boolean) {
        hubCameraActive = active
    }

    fun updateHubCameraBitmap(bmp: Bitmap) {
        pendingHubCamBitmap = bmp
        hubCamBitmapDirty = true
    }

    // ── SBS Video helper methods ──
    fun setBackgroundBitmap(bmp: Bitmap) {
        pendingBgBitmap = bmp
        bgBitmapDirty = true
    }

    fun setMonoSource(mono: Boolean) {
        isMonoSource = mono
    }

    fun setVideoWorldLocked(locked: Boolean) {
        isVideoWorldLocked = locked
        if (!locked) {
            videoFullscreen = false
            videoWindowScale = 1.0f
        }
    }

    fun setVideoWindowOffset(x: Float, y: Float) {
        videoOffsetX = x
        videoOffsetY = y
    }

    fun setHeadAngles(yaw: Float, pitch: Float, roll: Float) {
        headYaw = yaw
        headPitch = pitch
        headRoll = roll
    }

    fun setVideoFullscreen(fullscreen: Boolean) {
        videoFullscreen = fullscreen
    }

    fun setVideoEnvMode(envMode: Boolean) {
        videoEnvMode = envMode
    }

    fun setVideoWindowScale(scale: Float) {
        videoWindowScale = scale.coerceIn(0.5f, 3.0f)
    }

    fun setVideoAspectRatio(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoAspect = width.toFloat() / height.toFloat()
        }
    }

    fun setWindowScreenRect(x: Float, y: Float, halfW: Float, halfH: Float, parallax: Float) {
        windowX = x.coerceIn(-1.5f, 1.5f)
        windowY = y.coerceIn(-1.5f, 1.5f)
        windowHalfW = halfW.coerceIn(0.02f, 1.5f)
        windowHalfH = halfH.coerceIn(0.02f, 1.5f)
        windowParallax = parallax
    }

    fun updateWindowBitmap(bmp: Bitmap, onUploaded: () -> Unit) {
        pendingWindowRelease?.invoke()
        pendingWindowBitmap = bmp
        pendingWindowRelease = onUploaded
        windowBitmapDirty = true
    }

    fun screenToWindowUv(px: Float, py: Float, viewWidthPx: Int, viewHeightPx: Int): FloatArray? =
        computeWindowUv(px, py, viewWidthPx, viewHeightPx, clamp = false)

    fun screenToWindowUvClamped(px: Float, py: Float, viewWidthPx: Int, viewHeightPx: Int): FloatArray =
        computeWindowUv(px, py, viewWidthPx, viewHeightPx, clamp = true) ?: floatArrayOf(0.5f, 0.5f)

    private fun computeWindowUv(
        px: Float, py: Float, viewWidthPx: Int, viewHeightPx: Int, clamp: Boolean
    ): FloatArray? {
        if (!windowVisible || viewWidthPx <= 0 || viewHeightPx <= 0) return null

        val halfWpx = viewWidthPx / 2f
        val isLeftEye = px < halfWpx
        val localX = if (isLeftEye) px else px - halfWpx
        val eyeViewportW = if (isLeftEye) halfWpx else viewWidthPx - halfWpx
        val ndcX = (localX / eyeViewportW) * 2f - 1f
        val ndcY = 1f - (py / viewHeightPx) * 2f

        val centerShift = if (isLeftEye) -lensSeparation else lensSeparation
        val posX = ndcX - centerShift
        val posY = ndcY

        val eyeSign = if (isLeftEye) -1f else 1f
        val wx = posX - (windowX + eyeSign * windowParallax)
        val wy = posY - windowY

        if (!clamp && (kotlin.math.abs(wx) > windowHalfW || kotlin.math.abs(wy) > windowHalfH)) return null

        val u = ((wx / windowHalfW) * 0.5f + 0.5f).coerceIn(0f, 1f)
        val v = (0.5f - (wy / windowHalfH) * 0.5f).coerceIn(0f, 1f)
        return floatArrayOf(u, v)
    }

    private fun buildProgram(vertexSrc: String, fragSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Error enlazando programa GL: $log")
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Error compilando shader: $log")
        }
        return shader
    }

    fun release() {
        try { inputSurface?.release() } catch (_: Exception) {}
        try { surfaceTexture?.release() } catch (_: Exception) {}
    }
}
