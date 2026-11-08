package redcon.desktop;

/**
 * Turns a monotonic nanosecond source into wall-clock ticks and fixed updates.
 *
 * <p>Rendering is deliberately absent from this class. A window may render at
 * 30, 60, 144, or several hundred frames per second; callers receive the same
 * 60 simulation updates per elapsed second. Build and commencement deadlines
 * use {@link Sample#elapsedTicks()} so a slow frame or temporary stall cannot
 * lengthen a nominal wall-clock deadline.
 *
 * <p>Simulation catch-up is capped. After a long stall, old whole updates are
 * dropped rather than replayed over many subsequent renders. The sub-update
 * remainder is retained, so ordinary frame-rate jitter does not accumulate
 * drift.
 */
public final class DuelFrameClock {

    public static final int UPDATES_PER_SECOND = 60;
    public static final int MAX_UPDATES_PER_RENDER = 8;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MAX_SIMULATION_FRAME_NANOS = 250_000_000L;

    /** One sampled render interval. */
    public static final class Sample {
        private static final Sample ZERO = new Sample(0, 0, 0);

        private final int updates;
        private final long elapsedTicks;
        private final int droppedUpdates;

        private Sample(int updates, long elapsedTicks, int droppedUpdates) {
            this.updates = updates;
            this.elapsedTicks = elapsedTicks;
            this.droppedUpdates = droppedUpdates;
        }

        /** Fixed 60 Hz simulation updates due before this render. */
        public int updates() {
            return updates;
        }

        /** Actual monotonic elapsed time, converted to REDCON clock ticks. */
        public long elapsedTicks() {
            return elapsedTicks;
        }

        /** Whole simulation updates discarded by the anti-spiral cap. */
        public int droppedUpdates() {
            return droppedUpdates;
        }
    }

    private boolean initialized;
    private long previousNanos;
    private long updateRemainder;
    private long tickRemainder;

    /** Start or restart timing at a known monotonic instant. */
    public void reset(long nowNanos) {
        initialized = true;
        previousNanos = nowNanos;
        updateRemainder = 0;
        tickRemainder = 0;
    }

    /** Sample a new monotonic instant. The first sample establishes the epoch. */
    public Sample sample(long nowNanos) {
        if (!initialized) {
            reset(nowNanos);
            return Sample.ZERO;
        }

        long elapsedNanos = nowNanos - previousNanos;
        previousNanos = nowNanos;
        if (elapsedNanos <= 0) {
            return Sample.ZERO;
        }

        long elapsedTicks = toTicks(elapsedNanos);
        long simulationNanos = Math.min(elapsedNanos, MAX_SIMULATION_FRAME_NANOS);
        long updateUnits = updateRemainder
                         + simulationNanos * UPDATES_PER_SECOND;
        long scheduled = updateUnits / NANOS_PER_SECOND;
        updateRemainder = updateUnits % NANOS_PER_SECOND;

        int updates = (int) Math.min(scheduled, MAX_UPDATES_PER_RENDER);
        long dropped = scheduled - updates;
        int droppedUpdates = (int) Math.min(dropped, Integer.MAX_VALUE);
        return new Sample(updates, elapsedTicks, droppedUpdates);
    }

    private long toTicks(long elapsedNanos) {
        long wholeSeconds = elapsedNanos / NANOS_PER_SECOND;
        long partialNanos = elapsedNanos % NANOS_PER_SECOND;
        long partialUnits = tickRemainder
                          + partialNanos * Duel.TICKS_PER_SECOND;
        long ticks = wholeSeconds * Duel.TICKS_PER_SECOND
                   + partialUnits / NANOS_PER_SECOND;
        tickRemainder = partialUnits % NANOS_PER_SECOND;
        return ticks;
    }
}
