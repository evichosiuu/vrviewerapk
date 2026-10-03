package com.example.vrviewer

/**
 * Interactúa con el CONTENIDO de la ventana del Menú Hub (la página
 * web) usando el dedo índice extendido o pellizco (pinch 👌) — distinto
 * del agarre que HubWindowController usa para mover/redimensionar el marco.
 *
 * Mientras se "apunta" dentro del área de la ventana, se reenvían
 * eventos de touch reales (DOWN/MOVE/UP) al WebView. Es el propio
 * WebView el que decide si fue un tap (poco movimiento) o un
 * scroll/drag (más movimiento) — igual que en una pantalla táctil.
 */
class HubPointerController(
    private val window: HubWindowController,
    private val onTouchDown: (u: Float, v: Float) -> Unit,
    private val onTouchMove: (u: Float, v: Float) -> Unit,
    private val onTouchUp: (u: Float, v: Float) -> Unit,
    private val onPointerUpdate: ((u: Float, v: Float, active: Boolean) -> Unit)? = null
) {
    private class HandState {
        var touching = false
        var lastU = 0.5f
        var lastV = 0.5f
        var wasPinching = false
        var isPointerActive = false
    }

    private val leftState = HandState()
    private val rightState = HandState()

    companion object {
        private const val PINCH_START_THRESHOLD   = 0.65f
        private const val PINCH_RELEASE_THRESHOLD = 0.40f
        private const val DEPTH_GATE              = 0.40f // margen en Z
    }

    fun update(left: HandPose, right: HandPose) {
        // Si una mano está moviendo/redimensionando el marco, no
        // interpretamos gestos de apuntado (evita ambigüedad).
        if (window.isGrabbed()) {
            release(leftState)
            release(rightState)
            onPointerUpdate?.invoke(0.5f, 0.5f, false)
            return
        }

        updateHand(left, leftState)
        updateHand(right, rightState)

        // Combinar el estado del puntero entre ambas manos para evitar que una mano inactiva
        // sobrescriba la posición/estado de la mano activa.
        when {
            rightState.isPointerActive -> {
                onPointerUpdate?.invoke(rightState.lastU, rightState.lastV, true)
            }
            leftState.isPointerActive -> {
                onPointerUpdate?.invoke(leftState.lastU, leftState.lastV, true)
            }
            else -> {
                val lastU = if (right.tracked) rightState.lastU else leftState.lastU
                val lastV = if (right.tracked) rightState.lastV else leftState.lastV
                onPointerUpdate?.invoke(lastU, lastV, false)
            }
        }
    }

    private fun release(state: HandState) {
        if (state.touching) {
            state.touching = false
            onTouchUp(state.lastU, state.lastV)
        }
        state.isPointerActive = false
    }

    private fun updateHand(hand: HandPose, state: HandState) {
        if (!hand.tracked || !window.visible) {
            state.wasPinching = false
            release(state)
            return
        }

        val insideXY = kotlin.math.abs(hand.x - window.screenX) < window.screenHalfW &&
                kotlin.math.abs(hand.y - window.screenY) < window.screenHalfH
        val insideZ = kotlin.math.abs(-hand.z - window.depthMeters) < DEPTH_GATE

        if (!insideXY || !insideZ) {
            state.wasPinching = false
            release(state)
            return
        }

        val u = (((hand.x - window.screenX) / window.screenHalfW) * 0.5f + 0.5f).coerceIn(0f, 1f)
        val v = (0.5f - ((hand.y - window.screenY) / window.screenHalfH) * 0.5f).coerceIn(0f, 1f)
        state.lastU = u
        state.lastV = v
        state.isPointerActive = true

        val startPinch = hand.pinch >= PINCH_START_THRESHOLD || hand.clicked
        val holdPinch  = hand.pinch >= PINCH_RELEASE_THRESHOLD || hand.clicked

        if (!state.touching) {
            if (startPinch) {
                state.touching = true
                onTouchDown(u, v)
            }
        } else {
            if (holdPinch) {
                onTouchMove(u, v)
            } else {
                state.touching = false
                onTouchUp(u, v)
            }
        }
    }
}
