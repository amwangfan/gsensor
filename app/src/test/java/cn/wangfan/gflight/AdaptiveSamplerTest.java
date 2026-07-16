package cn.wangfan.gflight;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdaptiveSamplerTest {
    private static final long S = 1_000_000_000L;

    @Test public void quietSignalBecomesStableAndWritesEveryFiveSeconds() {
        AdaptiveSampler sampler = new AdaptiveSampler();
        assertTrue(sampler.onSample(0, 1.0).shouldLog);
        AdaptiveSampler.Decision decision = null;
        for (int i = 1; i <= 80; i++) decision = sampler.onSample(i * S / 10, 1.0);
        assertEquals(AdaptiveSampler.Mode.STABLE, decision.mode);
        assertEquals(200_000, AdaptiveSampler.samplingPeriodUs(decision.mode));
        assertFalse(sampler.onSample(9 * S, 1.0).shouldLog);
        assertTrue(sampler.onSample(13 * S, 1.0).shouldLog);
    }

    @Test public void sustainedChangeEnablesActiveModeAfterConfirmation() {
        AdaptiveSampler sampler = new AdaptiveSampler();
        sampler.onSample(0, 1.0);
        assertEquals(AdaptiveSampler.Mode.NORMAL, sampler.onSample(S, 1.08).mode);
        assertEquals(AdaptiveSampler.Mode.NORMAL, sampler.onSample(S + 100_000_000L, 1.08).mode);
        AdaptiveSampler.Decision decision = sampler.onSample(S + 200_000_000L, 1.08);
        assertEquals(AdaptiveSampler.Mode.ACTIVE, decision.mode);
        assertTrue(decision.modeChanged);
        assertEquals(20_000, AdaptiveSampler.samplingPeriodUs(decision.mode));
    }

    @Test public void isolatedBumpIsLoggedButDoesNotResetStableState() {
        AdaptiveSampler sampler = new AdaptiveSampler();
        sampler.onSample(0, 1.0);
        for (int i = 1; i <= 80; i++) sampler.onSample(i * S / 10, 1.0);
        AdaptiveSampler.Decision bump = sampler.onSample(8 * S + 200_000_000L, 1.09);
        assertEquals(AdaptiveSampler.Mode.STABLE, bump.mode);
        assertTrue(bump.shouldLog);
        assertEquals(AdaptiveSampler.Mode.STABLE,
                sampler.onSample(8 * S + 400_000_000L, 1.0).mode);
    }

    @Test public void variationInsideToleranceStillCountsAsQuiet() {
        AdaptiveSampler sampler = new AdaptiveSampler();
        sampler.onSample(0, 1.0);
        for (int i = 1; i <= 90; i++) {
            double smallVariation = i % 2 == 0 ? 1.04 : 0.96;
            sampler.onSample(i * S / 10, smallVariation);
        }
        assertEquals(AdaptiveSampler.Mode.STABLE, sampler.getMode());
    }

    @Test public void largeChangeActivatesImmediately() {
        AdaptiveSampler sampler = new AdaptiveSampler();
        sampler.onSample(0, 1.0);
        assertEquals(AdaptiveSampler.Mode.ACTIVE, sampler.onSample(S, 1.20).mode);
    }

    @Test public void activeModeHoldsForTenSeconds() {
        AdaptiveSampler sampler = new AdaptiveSampler();
        sampler.onSample(0, 1.0);
        sampler.onSample(S, 1.08);
        sampler.onSample(S + 100_000_000L, 1.08);
        sampler.onSample(S + 200_000_000L, 1.08);
        for (int i = 1; i <= 100; i++) {
            sampler.onSample(S + 200_000_000L + i * 20_000_000L, 1.08);
        }
        assertEquals(AdaptiveSampler.Mode.ACTIVE, sampler.onSample(10 * S, 1.08).mode);
        assertEquals(AdaptiveSampler.Mode.STABLE, sampler.onSample(12 * S, 1.08).mode);
    }

    @Test public void identityMatrixLeavesVectorUnchanged() {
        float[] result = CoordinateMapper.deviceToWorld(
                new float[]{1,0,0, 0,1,0, 0,0,1}, 1, 2, 3);
        assertEquals(1f, result[0], 0f);
        assertEquals(2f, result[1], 0f);
        assertEquals(3f, result[2], 0f);
    }
}
