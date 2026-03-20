package dev.hapax.watch.notification

/**
 * Vibration patterns for hapax notification types.
 *
 * Format: longArrayOf(delay, vibrate, pause, vibrate, ...)
 * Amplitude arrays: intArrayOf(0, amplitude, 0, amplitude, ...) where 0 = off,
 * 1-255 = amplitude level.
 *
 * Stimmung patterns encode system state into nuanced vibrations so the operator
 * can feel the system's stance through the watch. Each pattern has a distinct
 * temporal signature.
 */
object HapticPatterns {
    // ── Existing patterns ──────────────────────────────────────────────────

    /** tap-pause-tap — presence check from workstation */
    val PRESENCE_CHECK = longArrayOf(0, 100, 150, 100)

    /** buzz-pause-buzz — urgent notification */
    val URGENT = longArrayOf(0, 500, 200, 500)

    /** three gentle taps — briefing ready */
    val BRIEFING = longArrayOf(0, 50, 100, 50, 100, 50)

    /** single strong tap — voice pipeline ready */
    val VOICE_READY = longArrayOf(0, 200)

    // ── Stimmung patterns ──────────────────────────────────────────────────

    /** Single 30ms tap, amplitude 80 — all nominal, gentle acknowledgment */
    val STIMMUNG_CALM = longArrayOf(0, 30)
    val STIMMUNG_CALM_AMPLITUDES = intArrayOf(0, 80)

    /** Two 50ms taps, 200ms gap, amplitude 120 — system working harder */
    val STIMMUNG_CAUTIOUS = longArrayOf(0, 50, 200, 50)
    val STIMMUNG_CAUTIOUS_AMPLITUDES = intArrayOf(0, 120, 0, 120)

    /** Three 40ms taps accelerating (300ms→200ms→100ms gap), amplitude 160 — system stressed */
    val STIMMUNG_DEGRADED = longArrayOf(0, 40, 300, 40, 200, 40)
    val STIMMUNG_DEGRADED_AMPLITUDES = intArrayOf(0, 160, 0, 160, 0, 160)

    /** Long 400ms low-amplitude (60) buzz — "I see your stress, I'm backing off" */
    val STRESS_ACK = longArrayOf(0, 400)
    val STRESS_ACK_AMPLITUDES = intArrayOf(0, 60)

    /** Rising amplitude sweep: 40→120→200 over 300ms — activity transition predicted */
    val TRANSITION = longArrayOf(0, 100, 0, 100, 0, 100)
    val TRANSITION_AMPLITUDES = intArrayOf(0, 40, 0, 120, 0, 200)

    // Note: STIMMUNG_FLOW sends nothing — silence IS the signal.
    // Handled in HapticNotificationListener by not vibrating.
}
