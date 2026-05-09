package app.kofipod.snippets

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
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
    fun modulateAt_is_deterministic_for_same_inputs() {
        val base = gen.generate("snip-abc123")
        val a = gen.modulateAt(base, "snip-abc123", presentationTimeUs = 500_000L)
        val b = gen.modulateAt(base, "snip-abc123", presentationTimeUs = 500_000L)
        assertTrue(a.bars.contentEquals(b.bars))
    }

    @Test
    fun modulateAt_changes_quantitatively_across_a_quarter_cycle() {
        // 41_667 µs ≈ a quarter cycle at 6 Hz. Asserting on the *mean* mod-factor
        // delta (not just contentEquals) catches a regression where the function
        // returns the same array off-by-one ULP — that would still pass !contentEquals
        // but isn't real motion. 0.1f is well above floating-point noise; real
        // animation produces deltas well over 0.3f at quarter-cycle.
        val base = gen.generate("snip-motion")
        val frame0 = gen.modulateAt(base, "snip-motion", presentationTimeUs = 0L)
        val frameQuarter = gen.modulateAt(base, "snip-motion", presentationTimeUs = 41_667L)
        val deltas =
            base.bars.indices.map { i ->
                val f0 = if (base.bars[i] > 0f) frame0.bars[i] / base.bars[i] else 0f
                val fQ = if (base.bars[i] > 0f) frameQuarter.bars[i] / base.bars[i] else 0f
                abs(f0 - fQ)
            }
        val meanDelta = deltas.average().toFloat()
        assertTrue(meanDelta > 0.1f, "quarter-cycle mean delta too small: $meanDelta")
    }

    @Test
    fun modulateAt_is_periodic_at_full_cycle() {
        // At exactly one full cycle (1/6 s = 166_667 µs at 6 Hz) the modulation
        // factors should equal those at t=0. If sin were replaced by something
        // non-periodic at this frequency, this would break.
        val base = gen.generate("snip-period")
        val frame0 = gen.modulateAt(base, "snip-period", presentationTimeUs = 0L)
        val frameOneCycle = gen.modulateAt(base, "snip-period", presentationTimeUs = 166_667L)
        for (i in base.bars.indices) {
            assertEquals(frame0.bars[i], frameOneCycle.bars[i], absoluteTolerance = 0.01f)
        }
    }

    @Test
    fun modulateAt_keeps_bars_in_unit_range_with_full_cycle_sweep() {
        // Stress both bounds: base = 1.0 lifts the upper envelope to its ceiling,
        // and sweeping a whole cycle's worth of timestamps guarantees every bar
        // hits both peaks and troughs. A wrong-sign formula like
        // `0.5 - 0.5*sin(...)` would still stay in [0,1] in this test, but a
        // formula like `1 + sin(...)` (no halving) would exceed 1.0 and trip.
        val base = WaveformSamples(FloatArray(64) { 1.0f })
        // 167 samples across one 6 Hz cycle (≈1 ms each) — dense enough to land
        // near both extremes.
        var observedMin = Float.MAX_VALUE
        for (us in 0L..166_667L step 1_000L) {
            val frame = gen.modulateAt(base, "snip-sweep", presentationTimeUs = us)
            for (v in frame.bars) {
                assertTrue(v in 0f..1f, "modulated $v out of [0,1] at t=$us")
                if (v < observedMin) observedMin = v
            }
        }
        // Wrong-sign / wrong-DC bugs (e.g. `0.5 + sin(...)` instead of `0.5 + 0.5*sin(...)`)
        // would never reach the lower envelope. Confirm we actually do.
        assertTrue(observedMin <= 0.05f, "lower envelope never reached, min=$observedMin")
    }

    @Test
    fun modulateAt_matches_sin_formula_at_known_time() {
        // Pin the exact arithmetic. A sin→cos swap or a wrong time conversion
        // (1_000 vs 1_000_000) would change the expected value and fail this.
        // Single-bar sample so the expected output is hand-computable.
        val seed = "p"
        val tUs = 250_000L
        val freq = 6f
        val phaseSeed = seed.fold(0L) { acc, c -> acc * 31L + c.code } xor 0x5F3759DFL
        val phase = Random(phaseSeed).nextFloat() * 2f * PI.toFloat()
        val t = tUs / 1_000_000f
        val mod = 0.5f + 0.5f * sin(2f * PI.toFloat() * freq * t + phase)
        val expected = 0.8f * mod

        val base = WaveformSamples(FloatArray(1) { 0.8f })
        val result = gen.modulateAt(base, seed, presentationTimeUs = tUs, frequencyHz = freq)
        assertEquals(expected, result.bars[0], absoluteTolerance = 1e-6f)
    }

    @Test
    fun modulateAt_staggers_bar_phases() {
        // If all bars hit their peaks at the same instant the rendered MP4 would
        // pulse like a heartbeat instead of looking like a VU meter. We verify
        // the per-bar phases are staggered by computing the modulation factor
        // (mod = bars[i] / base[i]) at t=0 and confirming the spread is wide.
        val base = gen.generate("snip-stagger")
        val modulated = gen.modulateAt(base, "snip-stagger", presentationTimeUs = 0L)
        val factors =
            base.bars.zip(modulated.bars.toTypedArray()) { b, m ->
                if (b > 0f) m / b else 0f
            }
        val spread = factors.max() - factors.min()
        // 2π of phase spread → factors span roughly [0,1]; a spread of >0.6 is
        // a generous lower bound that catches the failure mode where every bar
        // gets the same phase.
        assertTrue(spread > 0.6f, "phase spread too tight: $spread")
    }

    @Test
    fun modulateAt_handles_empty_samples() {
        val empty = WaveformSamples(FloatArray(0))
        val result = gen.modulateAt(empty, "any-seed", presentationTimeUs = 1_000L)
        assertEquals(0, result.bars.size)
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
