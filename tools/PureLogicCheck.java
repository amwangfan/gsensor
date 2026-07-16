import cn.wangfan.gflight.AdaptiveSampler;
import cn.wangfan.gflight.CoordinateMapper;

/** Runs without the Android SDK, useful for a quick sampling-state smoke test. */
public final class PureLogicCheck {
    private static final long S = 1_000_000_000L;

    public static void main(String[] args) {
        AdaptiveSampler quiet = new AdaptiveSampler();
        check(quiet.onSample(0, 1.0).shouldLog, "first sample should be logged");
        for (int i = 1; i <= 80; i++) quiet.onSample(i * S / 10, 1.0);
        check(quiet.getMode() == AdaptiveSampler.Mode.STABLE, "quiet mode transition");
        check(!quiet.onSample(9 * S, 1.0).shouldLog, "stable write throttling");
        check(quiet.onSample(13 * S, 1.0).shouldLog, "stable heartbeat");

        AdaptiveSampler motion = new AdaptiveSampler();
        motion.onSample(0, 1.0);
        motion.onSample(S, 1.08);
        motion.onSample(S + 100_000_000L, 1.08);
        AdaptiveSampler.Decision active = motion.onSample(S + 200_000_000L, 1.08);
        check(active.mode == AdaptiveSampler.Mode.ACTIVE, "motion activates high precision");
        check(AdaptiveSampler.samplingPeriodUs(active.mode) == 20_000, "active sampling rate");

        float[] mapped = CoordinateMapper.deviceToWorld(
                new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, 1, 2, 3);
        check(mapped[0] == 1 && mapped[1] == 2 && mapped[2] == 3, "identity coordinate map");
        System.out.println("Pure logic checks passed");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
