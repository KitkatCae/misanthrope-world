package exp.CCnewmods.misanthrope_world.physics.perf;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal timing probe for finding real per-call costs in the structural/
 * pressure tick loops, ahead of any Quantified API migration decision.
 * <p>
 * ── Why this exists ───────────────────────────────────────────────────────────
 * Static code-reading gives complexity shape (e.g. "computeColumnLoad walks
 * up to 64 blocks, computeSpan BFS-explores up to 400") but not real
 * wall-clock cost — that depends on actual chunk-load patterns, JIT warmup,
 * cache locality, and how often the expensive branches (computeSpan,
 * connectedFailureBFS) actually fire in a real play session. This exists to
 * get real numbers out of an actual session instead of guessing from either
 * end.
 * <p>
 * ── Usage ─────────────────────────────────────────────────────────────────────
 * <pre>{@code
 * long t0 = System.nanoTime();
 * // ... do the work ...
 * PerfSampler.record("structural.evaluateBlock", System.nanoTime() - t0);
 * }</pre>
 * Call {@link #maybeLogAndReset(long)} once per tick (any one call site is
 * enough — it walks all registered probes) with the current game time; it
 * logs and resets every {@link #LOG_INTERVAL_TICKS} ticks. Completely inert
 * (one volatile read, no allocation) when {@link #ENABLED} is false.
 * <p>
 * ── Turning it on ─────────────────────────────────────────────────────────────
 * Not wired to a Forge config value on purpose — this is meant for short,
 * deliberate profiling sessions, not a permanent settings-menu toggle. Flip
 * {@link #ENABLED} to {@code true}, rebuild, play for a few minutes doing
 * whatever's expensive (a big explosion, a chunk with lots of structural
 * blocks loading, etc.), then flip it back off. Output goes to the normal log
 * at INFO level under the {@code MisanthropeWorld/Perf} logger name.
 */
public final class PerfSampler {

    /** Flip to true, rebuild, profile, flip back — see class doc. */
    public static volatile boolean ENABLED = false;

    private static final int LOG_INTERVAL_TICKS = 200; // 10s at 20 TPS

    private static final Logger LOGGER = LogManager.getLogger("MisanthropeWorld/Perf");

    private static final ConcurrentHashMap<String, Probe> PROBES = new ConcurrentHashMap<>();
    private static final AtomicLong LAST_LOG_TICK = new AtomicLong(-1);

    private PerfSampler() {
    }

    private static final class Probe {
        final LongAdder count = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong(0);
    }

    /** Records one timed occurrence of {@code name}. No-op if {@link #ENABLED} is false. */
    public static void record(String name, long elapsedNanos) {
        if (!ENABLED) return;
        Probe p = PROBES.computeIfAbsent(name, k -> new Probe());
        p.count.increment();
        p.totalNanos.add(elapsedNanos);
        p.maxNanos.accumulateAndGet(elapsedNanos, Math::max);
    }

    /**
     * Convenience for the common "time this block" shape without the
     * boilerplate at every call site. Still pays the {@code System.nanoTime()}
     * cost even when disabled (two calls), since the caller needs a
     * before/after pair regardless — use the manual {@link #record} form on
     * truly hot paths if that matters.
     */
    public static void timed(String name, Runnable work) {
        if (!ENABLED) {
            work.run();
            return;
        }
        long t0 = System.nanoTime();
        work.run();
        record(name, System.nanoTime() - t0);
    }

    /**
     * Call once per tick from any one call site (e.g. StructuralStressField's
     * onServerTick). Logs a summary and resets every {@link #LOG_INTERVAL_TICKS}
     * ticks; no-op otherwise or if disabled.
     */
    public static void maybeLogAndReset(long gameTime) {
        if (!ENABLED) return;
        long last = LAST_LOG_TICK.get();
        if (last != -1 && gameTime - last < LOG_INTERVAL_TICKS) return;
        if (!LAST_LOG_TICK.compareAndSet(last, gameTime)) return; // another thread already logging this window

        if (PROBES.isEmpty()) return;

        StringBuilder sb = new StringBuilder("[Perf] last ~").append(LOG_INTERVAL_TICKS).append(" ticks:\n");
        PROBES.forEach((name, p) -> {
            long n = p.count.sumThenReset();
            long total = p.totalNanos.sumThenReset();
            long max = p.maxNanos.getAndSet(0);
            if (n == 0) return;
            double avgMicros = (total / (double) n) / 1000.0;
            double maxMicros = max / 1000.0;
            double totalMillis = total / 1_000_000.0;
            sb.append(String.format(
                    "  %-40s calls=%-6d avg=%8.2fµs max=%8.2fµs total=%7.2fms%n",
                    name, n, avgMicros, maxMicros, totalMillis));
        });
        LOGGER.info(sb.toString());
    }
}
