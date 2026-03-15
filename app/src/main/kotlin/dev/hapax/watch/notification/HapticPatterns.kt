package dev.hapax.watch.notification

/**
 * Vibration patterns for hapax notification types.
 * Format: longArrayOf(delay, vibrate, pause, vibrate, ...)
 */
object HapticPatterns {
    /** tap-pause-tap — presence check from workstation */
    val PRESENCE_CHECK = longArrayOf(0, 100, 150, 100)

    /** buzz-pause-buzz — urgent notification */
    val URGENT = longArrayOf(0, 500, 200, 500)

    /** three gentle taps — briefing ready */
    val BRIEFING = longArrayOf(0, 50, 100, 50, 100, 50)

    /** single strong tap — voice pipeline ready */
    val VOICE_READY = longArrayOf(0, 200)
}
