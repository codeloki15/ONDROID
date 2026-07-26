package com.locallink.pro.service.voice

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Checks [OpenWakeWordDetector] against openwakeword's own Python implementation.
 *
 * This exists because every numerical detail in that port fails *silently*: feed the
 * melspectrogram model normalised audio instead of int16-valued floats, drop the `x/10 + 2`
 * transform, or slide the 76-frame window by the wrong stride, and you get a detector that
 * runs, logs nothing, costs battery, and never fires. Nothing else in the app would notice.
 *
 * Fixtures come from `tools/wakeword-training/make_test_fixture.py`, which records what the
 * reference scores chunk-by-chunk on held-out clips. Regenerate them whenever the model is
 * retrained.
 */
class OpenWakeWordDetectorTest {

    private val res = File("src/test/resources/oww")
    private val assets = File("src/main/assets/oww")

    private fun fixturesPresent() =
        res.resolve("expected.json").exists() && res.resolve("hey_omni.onnx").exists()

    private fun detector(threshold: Float) = OpenWakeWordDetector(
        melspecModel = assets.resolve("melspectrogram.onnx").readBytes(),
        embeddingModel = assets.resolve("embedding_model.onnx").readBytes(),
        wakeModel = res.resolve("hey_omni.onnx").readBytes(),
        threshold = threshold,
    )

    /** Minimal 16-bit PCM WAV reader — enough for the mono 16 kHz fixtures. */
    private fun readWav(f: File): ShortArray {
        val bytes = f.readBytes()
        var pos = 12  // skip RIFF header
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val out = ShortArray(size / 2)
                ByteBuffer.wrap(bytes, pos + 8, size).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().get(out)
                return out
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in ${f.name}")
    }

    /** Replay a clip in 80 ms steps, collecting one score per step. */
    private fun scoreChunks(pcm: ShortArray): List<Float> {
        // Threshold above 1.0 so nothing ever "fires" — a detection would start the
        // refractory period and blank the scores we're trying to compare.
        val det = detector(threshold = 2f)
        val scores = mutableListOf<Float>()
        var i = 0
        while (i + CHUNK <= pcm.size) {
            det.accept(pcm.copyOfRange(i, i + CHUNK), CHUNK)
            scores += det.lastScore
            i += CHUNK
        }
        det.close()
        return scores
    }

    private fun expected(label: String): List<Float> {
        val json = JSONObject(res.resolve("expected.json").readText()).getJSONArray(label)
        return (0 until json.length()).map { json.getDouble(it).toFloat() }
    }

    @Test
    fun `matches the python reference chunk for chunk`() {
        assumeTrue("fixtures absent — run make_test_fixture.py", fixturesPresent())

        for (label in listOf("positive", "negative")) {
            val want = expected(label)
            val got = scoreChunks(readWav(res.resolve("$label.wav")))

            assertTrue("$label: got ${got.size} chunks, reference had ${want.size}",
                got.size == want.size)

            // Skip the warmup: the reference primes its feature buffer with embeddings of
            // random noise, this port gates scoring until it has WINDOW real frames. The two
            // only become comparable once genuine audio fills the window.
            for (i in WARMUP until want.size) {
                assertTrue(
                    "$label chunk $i: port=${got[i]} reference=${want[i]}",
                    abs(got[i] - want[i]) < TOLERANCE,
                )
            }
        }
    }

    @Test
    fun `fires on a held-out positive and not on an adversarial negative`() {
        assumeTrue("fixtures absent — run make_test_fixture.py", fixturesPresent())

        val positive = scoreChunks(readWav(res.resolve("positive.wav"))).drop(WARMUP).max()
        val negative = scoreChunks(readWav(res.resolve("negative.wav"))).drop(WARMUP).max()

        assertTrue("positive peaked at $positive, below the default threshold",
            positive >= OpenWakeWordDetector.DEFAULT_THRESHOLD)
        assertTrue("negative peaked at $negative, at or above the default threshold",
            negative < OpenWakeWordDetector.DEFAULT_THRESHOLD)
    }

    @Test
    fun `chunking is independent of the audio block size`() {
        assumeTrue("fixtures absent — run make_test_fixture.py", fixturesPresent())

        // AudioRecord hands over whatever its buffer holds, which is not a multiple of 80 ms.
        // The remainder-carrying logic has to make that invisible.
        val pcm = readWav(res.resolve("positive.wav"))
        val even = scoreChunks(pcm)

        val det = detector(threshold = 2f)
        val ragged = mutableListOf<Float>()
        var i = 0
        var last = 0f
        for (size in generateSequence { listOf(517, 1280, 331, 2048, 100) }.flatten()) {
            if (i >= pcm.size) break
            val n = minOf(size, pcm.size - i)
            det.accept(pcm.copyOfRange(i, i + n), n)
            if (det.lastScore != last) { ragged += det.lastScore; last = det.lastScore }
            i += n
        }
        det.close()

        // Same peak reached, regardless of how the audio was sliced on the way in.
        assertTrue(
            "ragged blocks peaked at ${ragged.maxOrNull()}, even blocks at ${even.max()}",
            abs((ragged.maxOrNull() ?: 0f) - even.max()) < TOLERANCE,
        )
    }

    private companion object {
        const val CHUNK = 1280
        const val WARMUP = 16
        const val TOLERANCE = 1e-3f
    }
}
