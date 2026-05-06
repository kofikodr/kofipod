package app.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformGeneratorTest {
    private val gen = WaveformGenerator()

    @Test
    fun deterministic_for_same_seed() {
        val a = gen.generate("snip-abc123", barCount = 64)
        val b = gen.generate("snip-abc123", barCount = 64)
        assertTrue(a.bars.contentEquals(b.bars))
    }

    @Test
    fun different_seeds_produce_different_output() {
        val a = gen.generate("snip-abc123")
        val b = gen.generate("snip-def456")
        assertTrue(!a.bars.contentEquals(b.bars))
    }

    @Test
    fun bars_are_in_unit_range() {
        val w = gen.generate("snip-test", barCount = 64)
        for (v in w.bars) assertTrue(v in 0.0f..1.0f, "bar $v out of [0,1]")
    }

    @Test
    fun bar_count_matches_param() {
        val w = gen.generate("snip-x", barCount = 32)
        assertEquals(32, w.bars.size)
    }

    @Test
    fun smoothing_avoids_constant_runs() {
        // Nudge invariant: no run of 4+ adjacent values are exactly equal,
        // for any seed. The smoother correlates neighbours; the nudge step
        // breaks exact ties; together they prevent flat sections that would
        // render as visual gaps.
        val seeds = listOf("snip-real", "", "aaaa", "snip-abc123", "snip-def456", "x")
        for (seed in seeds) {
            val w = gen.generate(seed, barCount = 64)
            var run = 1
            var maxRun = 1
            for (i in 1 until w.bars.size) {
                if (w.bars[i] == w.bars[i - 1]) run++ else run = 1
                if (run > maxRun) maxRun = run
            }
            assertTrue(maxRun < 4, "seed='$seed': constant run of $maxRun bars")
        }
    }
}
