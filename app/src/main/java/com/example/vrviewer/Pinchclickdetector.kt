package com.example.vrviewer

/**
 * Detecta el flanco de subida del gesto "pinch" (pulgar + índice
 * tocándose) para usarlo como un click discreto (mouse-down), no como
 * algo que se dispara en cada frame mientras se mantienen los dedos
 * juntos.
 *
 * Se necesita UNA instancia por mano (igual que HandPoseSmoother),
 * porque guarda el estado "¿estaba pinchando el frame anterior?".
 *
 * A propósito NO recalcula la distancia entre landmarks[4] y
 * landmarks[8] desde cero: reutiliza el `pinch` (0f..1f) que ya
 * calcula HandGesture.compute(), que ya viene normalizado por el
 * tamaño de la mano (dist(muñeca, nudillo medio)).
 */
class PinchClickDetector(
    // Umbral para EMPEZAR el click. pinch=1f es "dedos tocándose del todo".
    private val pressThreshold: Float = 0.8f,
    // Umbral (más bajo) para SOLTAR el click. La separación entre ambos
    // valores es histéresis: evita que el estado "parpadee" (varios
    // clicks seguidos) si el pinch queda temblando justo en el borde
    // de un único umbral.
    private val releaseThreshold: Float = 0.6f
) {
    private var isPinching = false

    /**
     * Llamar una vez por frame con el `pinch` (0f..1f) de esa mano.
     * @return true SOLO en el frame donde el click acaba de empezar
     * (transición no-pinch -> pinch), no en cada frame que se mantiene.
     */
    fun update(pinch: Float): Boolean {
        val wasPinching = isPinching
        isPinching = if (isPinching) pinch > releaseThreshold else pinch > pressThreshold
        return isPinching && !wasPinching
    }

    /** Returns true as long as the pinch condition remains held. */
    fun isHeld(): Boolean = isPinching

    fun reset() {
        isPinching = false
    }
}
