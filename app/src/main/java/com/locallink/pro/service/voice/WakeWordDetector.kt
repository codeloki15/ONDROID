package com.locallink.pro.service.voice

/**
 * The wake-word decision, separated from the mic plumbing.
 *
 * [WakeWordEngine] owns the AudioRecord lifecycle and the thread discipline the mic
 * requires (see the single-owner constraint in CLAUDE.md); everything about *what counts
 * as a wake* lives behind this interface, so the two can change independently.
 */
interface WakeWordDetector {
    /**
     * Feed the next block of 16 kHz mono 16-bit PCM. Returns true when the wake word
     * has just been detected. Implementations must tolerate any block size.
     */
    fun accept(pcm: ShortArray, n: Int): Boolean

    /** Drop all accumulated audio state — called after a wake, before the next session. */
    fun reset()

    /** Release native resources. */
    fun close()
}
