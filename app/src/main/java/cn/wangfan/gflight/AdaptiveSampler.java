package cn.wangfan.gflight;

/** Pure state machine used by the service and covered by local unit tests. */
public final class AdaptiveSampler {
    public enum Mode { NORMAL, STABLE, ACTIVE }

    public static final class Decision {
        public final Mode mode;
        public final boolean modeChanged;
        public final boolean shouldLog;

        Decision(Mode mode, boolean modeChanged, boolean shouldLog) {
            this.mode = mode;
            this.modeChanged = modeChanged;
            this.shouldLog = shouldLog;
        }
    }

    private static final long SECOND_NS = 1_000_000_000L;
    private static final long QUIET_TO_STABLE_NS = 8 * SECOND_NS;
    private static final long ACTIVE_HOLD_NS = 10 * SECOND_NS;
    // Normal sensor noise and light cabin vibration remain inside this band.
    private static final double QUIET_TOLERANCE_G = 0.060;
    private static final double IMMEDIATE_CHANGE_G = 0.150;
    private static final int CHANGE_CONFIRM_SAMPLES = 3;

    private Mode mode = Mode.NORMAL;
    private double emaG = Double.NaN;
    private double lastG = Double.NaN;
    private long quietSinceNs;
    private long activeUntilNs;
    private long lastLogNs = Long.MIN_VALUE;
    private int consecutiveOutsideTolerance;

    public synchronized Decision onSample(long timestampNs, double magnitudeG) {
        if (!Double.isFinite(emaG)) {
            emaG = magnitudeG;
            lastG = magnitudeG;
            quietSinceNs = timestampNs;
            lastLogNs = timestampNs;
            return new Decision(mode, false, true);
        }

        double deviationG = Math.abs(magnitudeG - emaG);
        boolean outsideTolerance = deviationG >= QUIET_TOLERANCE_G;
        if (outsideTolerance) consecutiveOutsideTolerance++;
        else consecutiveOutsideTolerance = 0;
        boolean significant = deviationG >= IMMEDIATE_CHANGE_G
                || consecutiveOutsideTolerance >= CHANGE_CONFIRM_SAMPLES;
        Mode oldMode = mode;

        if (significant) {
            consecutiveOutsideTolerance = 0;
            activeUntilNs = timestampNs + ACTIVE_HOLD_NS;
            quietSinceNs = timestampNs;
            mode = Mode.ACTIVE;
        } else if (timestampNs < activeUntilNs) {
            mode = Mode.ACTIVE;
        } else if (timestampNs - quietSinceNs >= QUIET_TO_STABLE_NS) {
            mode = Mode.STABLE;
        } else {
            mode = Mode.NORMAL;
        }

        double alpha = mode == Mode.ACTIVE ? 0.08 : 0.03;
        emaG += alpha * (magnitudeG - emaG);
        lastG = magnitudeG;

        long interval = switch (mode) {
            case ACTIVE -> 100_000_000L;      // write at up to 10 Hz
            case NORMAL -> SECOND_NS;         // normal write rate: 1 Hz
            case STABLE -> 5 * SECOND_NS;     // quiet heartbeat: once / 5 s
        };
        // Keep isolated bumps in the CSV without treating them as sustained motion.
        boolean log = outsideTolerance || lastLogNs == Long.MIN_VALUE
                || timestampNs - lastLogNs >= interval;
        if (log) lastLogNs = timestampNs;
        return new Decision(mode, oldMode != mode, log);
    }

    public synchronized Mode getMode() {
        return mode;
    }

    public static int samplingPeriodUs(Mode mode) {
        return switch (mode) {
            case ACTIVE -> 20_000;    // request 50 Hz
            case NORMAL -> 100_000;   // request 10 Hz
            case STABLE -> 200_000;   // request 5 Hz
        };
    }

    public static int batchingLatencyUs(Mode mode) {
        return switch (mode) {
            case ACTIVE -> 200_000;
            case NORMAL -> 1_000_000;
            case STABLE -> 5_000_000;
        };
    }
}
