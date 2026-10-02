package com.example.vrviewer

/**
 * Detecta el flanco de subida del gesto "pinch" (pulgar + índice
 * tocándose) para usarlo como un click discreto (mouse-down), no como
 * algo que se dispara en cada frame mientras se mantienen los dedos
 * juntos.
 */
class PinchClickDetector(
    private val pressThreshold: Float = 0.8f,
    private val releaseThreshold: Float = 0.6f
) {
    private var isPinching = false

    fun update(pinch: Float): Boolean {
        val wasPinching = isPinching
        isPinching = if (isPinching) pinch > releaseThreshold else pinch > pressThreshold
        return isPinching && !wasPinching
    }

    fun isHeld(): Boolean = isPinching

    fun reset() {
        isPinching = false
    }
}
