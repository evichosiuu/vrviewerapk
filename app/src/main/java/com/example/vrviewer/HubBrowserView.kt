package com.example.vrviewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Navegador embebido para la ventana del Menú Hub. Vive fuera de pantalla
 * (adjunto al árbol de vistas para que Chromium componga, pero desplazado
 * lejos del viewport) y se vuelca a un Bitmap ~10 veces por segundo, que
 * alimenta glRenderer.updateWindowBitmap().
 */
@SuppressLint("SetJavaScriptEnabled")
class HubBrowserView(
    private val context: Context,
    private val hostLayout: ViewGroup,
    private val widthPx: Int = 2560,
    private val heightPx: Int = 1440,
    private val onFrame: (Bitmap, release: () -> Unit) -> Unit
) {
    private var webView: WebView? = null

    // ── Triple buffer con locking real ──
    private val BUF_COUNT = 3
    private val buffers = arrayOfNulls<Bitmap>(BUF_COUNT)
    private val bufferBusy = BooleanArray(BUF_COUNT)
    private var nextBufIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    @Volatile var grabbedIndicator = false

    // Position indicator (cursor dot) for VR hand pointing
    @Volatile var pointerU = 0.5f
    @Volatile var pointerV = 0.5f
    @Volatile var pointerActive = false

    private var lastTouchDownTime = 0L

    // ── Micro-stepping de scroll ──
    private var lastDispatchedX = 0f
    private var lastDispatchedY = 0f

    // ── Clamp de overscroll en el tope ──
    private var clampedAtTop = false
    private var clampAnchorY = 0f

    // ── Captura event-driven, throtteada ──
    private var lastCaptureTime = 0L

    companion object {
        private const val CAPTURE_INTERVAL_MS = 100L
        private const val MIN_CAPTURE_GAP_MS = 16L   // ~60fps tope para la captura "on-demand"
        private const val MAX_STEP_PX = 24f          // salto máximo por ACTION_MOVE despachado

        private const val VR_KEYBOARD_JS = """
        (function() {
          if (window.__vrKeyboardInstalled) return;
          window.__vrKeyboardInstalled = true;

          var css = `
            #__vrkb { position:fixed; left:0; right:0; bottom:0; z-index:2147483647;
              background:#1e1e24; padding:8px; display:none;
              font-family:sans-serif; box-shadow:0 -2px 8px rgba(0,0,0,.4); }
            #__vrkb .row { display:flex; justify-content:center; margin:4px 0; }
            #__vrkb button { flex:1; margin:3px; padding:12px 0; font-size:18px; font-weight:bold;
              border:none; border-radius:6px; background:#3a3a44; color:#fff; }
            #__vrkb button.wide { flex:3; }
            #__vrkb button.hide { flex:1; background:#6366f1; }
          `;
          var style = document.createElement('style');
          style.textContent = css;
          document.head.appendChild(style);

          var kb = document.createElement('div');
          kb.id = '__vrkb';
          var rows = ['1234567890', 'qwertyuiop', 'asdfghjkl', 'zxcvbnm'];
          rows.forEach(function(r) {
            var row = document.createElement('div');
            row.className = 'row';
            r.split('').forEach(function(ch) {
              var b = document.createElement('button');
              b.textContent = ch;
              row.appendChild(b);
            });
            kb.appendChild(row);
          });
          var lastRow = document.createElement('div');
          lastRow.className = 'row';
          var back = document.createElement('button'); back.textContent = '⌫';
          var space = document.createElement('button'); space.textContent = 'espacio'; space.className = 'wide';
          var enter = document.createElement('button'); enter.textContent = '↵';
          var hide = document.createElement('button'); hide.textContent = '▼'; hide.className = 'hide';
          lastRow.appendChild(back); lastRow.appendChild(space); lastRow.appendChild(enter); lastRow.appendChild(hide);
          kb.appendChild(lastRow);
          document.body.appendChild(kb);

          var target = null;

          function insert(text) {
            if (!target) return;
            var start = target.selectionStart != null ? target.selectionStart : target.value.length;
            var end = target.selectionEnd != null ? target.selectionEnd : target.value.length;
            var val = target.value || '';
            target.value = val.slice(0, start) + text + val.slice(end);
            var pos = start + text.length;
            try { target.setSelectionRange(pos, pos); } catch(e) {}
            target.dispatchEvent(new Event('input', { bubbles: true }));
          }

          function backspace() {
            if (!target) return;
            var start = target.selectionStart != null ? target.selectionStart : target.value.length;
            var end = target.selectionEnd != null ? target.selectionEnd : target.value.length;
            var val = target.value || '';
            if (start === end && start > 0) { start -= 1; }
            target.value = val.slice(0, start) + val.slice(end);
            try { target.setSelectionRange(start, start); } catch(e) {}
            target.dispatchEvent(new Event('input', { bubbles: true }));
          }

          kb.querySelectorAll('button').forEach(function(b) {
            b.addEventListener('mousedown', function(e) {
              e.preventDefault();
              if (b === back) backspace();
              else if (b === space) insert(' ');
              else if (b === enter) {
                var form = target && target.form;
                if (form) { (form.requestSubmit ? form.requestSubmit() : form.submit()); }
                else if (target) target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
              } else if (b === hide) {
                kb.style.display = 'none';
                if (target) target.blur();
              } else {
                insert(b.textContent);
              }
              if (target) target.focus();
            });
          });

          document.addEventListener('focusin', function(e) {
            var t = e.target;
            var editable = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable);
            if (editable) { target = t; kb.style.display = 'block'; }
          });
          document.addEventListener('focusout', function(e) {
            setTimeout(function() {
              if (document.activeElement === document.body || !document.activeElement) {
                kb.style.display = 'none';
              }
            }, 50);
          });
        })();
        """

        private const val VR_DISABLE_OVERSCROLL_JS = """
        (function() {
          if (window.__vrOverscrollGuardInstalled) return;
          window.__vrOverscrollGuardInstalled = true;

          var css = `
            html, body {
              overscroll-behavior: none !important;
              overscroll-behavior-y: none !important;
            }
          `;
          var style = document.createElement('style');
          style.textContent = css;
          document.head.appendChild(style);
          try {
            document.documentElement.style.overscrollBehavior = 'none';
            document.body.style.overscrollBehavior = 'none';
          } catch (e) {}

          function scrollTop() {
            return window.scrollY ||
                   document.documentElement.scrollTop ||
                   document.body.scrollTop || 0;
          }

          var startY = 0;
          var guarding = false;

          document.addEventListener('touchstart', function(e) {
            if (e.touches && e.touches.length > 0) {
              startY = e.touches[0].clientY;
            }
            guarding = false;
          }, { capture: true, passive: true });

          document.addEventListener('touchmove', function(e) {
            if (!e.touches || e.touches.length === 0) return;
            var curY = e.touches[0].clientY;
            var draggingDown = curY > startY;

            if (guarding || (scrollTop() <= 0 && draggingDown)) {
              guarding = true;
              e.preventDefault();
            }
          }, { capture: true, passive: false });

          document.addEventListener('touchend', function() {
            guarding = false;
          }, { capture: true, passive: true });
        })();
        """
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            captureFrame()
            if (running) handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    private fun isOnMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun runOnMainThread(block: () -> Unit) {
        if (isOnMainThread()) block() else handler.post(block)
    }

    fun updatePointer(u: Float, v: Float, active: Boolean) {
        pointerU = u
        pointerV = v
        pointerActive = active
        captureFrameThrottled()
    }

    fun start(initialUrl: String = "https://www.google.com") {
        if (webView != null) { load(initialUrl); return }

        val wv = WebView(context)
        wv.layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.settings.setSupportZoom(false)
        wv.settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile VR) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        wv.settings.offscreenPreRaster = true
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(VR_DISABLE_OVERSCROLL_JS, null)
                view.evaluateJavascript(VR_KEYBOARD_JS, null)
            }
        }

        wv.translationX = -10000f
        wv.visibility = View.VISIBLE
        hostLayout.addView(wv)
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, widthPx, heightPx)

        webView = wv
        for (i in 0 until BUF_COUNT) {
            buffers[i] = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bufferBusy[i] = false
        }
        nextBufIndex = 0
        lastDispatchedX = 0f
        lastDispatchedY = 0f
        lastCaptureTime = 0L
        clampedAtTop = false
        wv.loadUrl(initialUrl)

        running = true
        handler.post(captureRunnable)
    }

    fun load(url: String) { webView?.loadUrl(url) }
    fun goBack() { webView?.let { if (it.canGoBack()) it.goBack() } }
    fun goForward() { webView?.let { if (it.canGoForward()) it.goForward() } }

    fun tapAt(uNorm: Float, vNorm: Float) {
        runOnMainThread { tapAtInternal(uNorm, vNorm) }
    }

    private fun tapAtInternal(uNorm: Float, vNorm: Float) {
        val wv = webView ?: return
        val x = uNorm.coerceIn(0f, 1f) * widthPx
        val y = vNorm.coerceIn(0f, 1f) * heightPx
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up   = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
        wv.dispatchTouchEvent(down)
        wv.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
    }

    fun touchDown(uNorm: Float, vNorm: Float) {
        runOnMainThread { touchDownInternal(uNorm, vNorm) }
    }

    private fun touchDownInternal(uNorm: Float, vNorm: Float) {
        val wv = webView ?: return
        wv.requestFocus()
        val x = uNorm.coerceIn(0f, 1f) * widthPx
        val y = vNorm.coerceIn(0f, 1f) * heightPx
        lastTouchDownTime = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(lastTouchDownTime, lastTouchDownTime, MotionEvent.ACTION_DOWN, x, y, 0)
        wv.dispatchTouchEvent(ev)
        ev.recycle()
        lastDispatchedX = x
        lastDispatchedY = y
        clampedAtTop = false
        captureFrameThrottled()
    }

    fun touchMove(uNorm: Float, vNorm: Float) {
        runOnMainThread { touchMoveInternal(uNorm, vNorm) }
    }

    private fun touchMoveInternal(uNorm: Float, vNorm: Float) {
        val wv = webView ?: return
        val rawX = uNorm.coerceIn(0f, 1f) * widthPx
        val rawY = vNorm.coerceIn(0f, 1f) * heightPx

        val atTop = wv.scrollY <= 0
        if (atTop) {
            if (!clampedAtTop) {
                clampedAtTop = true
                clampAnchorY = lastDispatchedY
            }
            if (rawY >= clampAnchorY) {
                lastDispatchedX = rawX
                captureFrameThrottled()
                return
            } else {
                clampedAtTop = false
            }
        } else {
            clampedAtTop = false
        }

        val x = rawX
        val y = rawY
        val dx = x - lastDispatchedX
        val dy = y - lastDispatchedY
        val dist = kotlin.math.hypot(dx, dy)
        val steps = (dist / MAX_STEP_PX).toInt().coerceAtLeast(1)

        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val stepX = lastDispatchedX + dx * t
            val stepY = lastDispatchedY + dy * t
            val now = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(lastTouchDownTime, now, MotionEvent.ACTION_MOVE, stepX, stepY, 0)
            wv.dispatchTouchEvent(ev)
            ev.recycle()
        }

        lastDispatchedX = x
        lastDispatchedY = y
        captureFrameThrottled()
    }

    fun touchUp(uNorm: Float, vNorm: Float) {
        runOnMainThread { touchUpInternal(uNorm, vNorm) }
    }

    private fun touchUpInternal(uNorm: Float, vNorm: Float) {
        val wv = webView ?: return
        val x = uNorm.coerceIn(0f, 1f) * widthPx
        val y = vNorm.coerceIn(0f, 1f) * heightPx
        val now = SystemClock.uptimeMillis() + 40L

        val settle = MotionEvent.obtain(lastTouchDownTime, now, MotionEvent.ACTION_MOVE, x, y, 0)
        wv.dispatchTouchEvent(settle)
        settle.recycle()

        val ev = MotionEvent.obtain(lastTouchDownTime, now, MotionEvent.ACTION_UP, x, y, 0)
        wv.dispatchTouchEvent(ev)
        ev.recycle()
        captureFrameThrottled()
    }

    private fun captureFrameThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureTime < MIN_CAPTURE_GAP_MS) return
        lastCaptureTime = now
        captureFrame()
    }

    private fun captureFrame() {
        val wv = webView ?: return

        var idx = nextBufIndex
        var scanned = 0
        while (bufferBusy[idx] && scanned < BUF_COUNT) {
            idx = (idx + 1) % BUF_COUNT
            scanned++
        }
        if (bufferBusy[idx]) return

        val target = buffers[idx] ?: return
        try {
            val canvas = Canvas(target)
            wv.draw(canvas)

            // Feedback visual: dibujar puntero si está activo
            if (pointerActive) {
                val cx = pointerU * widthPx
                val cy = pointerV * heightPx

                val outerPaint = Paint().apply {
                    color = 0xFFFFFFFF.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                    isAntiAlias = true
                }
                val innerPaint = Paint().apply {
                    color = 0xEEFF3366.toInt()
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                canvas.drawCircle(cx, cy, 16f, innerPaint)
                canvas.drawCircle(cx, cy, 16f, outerPaint)
            }

            if (grabbedIndicator) {
                val paint = Paint().apply {
                    color = 0xFF6366F1.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 10f
                }
                canvas.drawRect(5f, 5f, widthPx - 5f, heightPx - 5f, paint)
            }

            bufferBusy[idx] = true
            nextBufIndex = (idx + 1) % BUF_COUNT

            onFrame(target) {
                bufferBusy[idx] = false
            }
        } catch (_: Exception) {
            bufferBusy[idx] = false
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(captureRunnable)
        webView?.let { hostLayout.removeView(it); it.destroy() }
        webView = null
        for (i in 0 until BUF_COUNT) { buffers[i] = null; bufferBusy[i] = false }
    }
}
