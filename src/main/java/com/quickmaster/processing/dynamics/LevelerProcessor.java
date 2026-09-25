package com.quickmaster.processing.dynamics;

import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import com.quickmaster.processing.dynamics.leveler.GainPlanner;
import com.quickmaster.processing.dynamics.leveler.LevelerAnalysisEngine;
import com.quickmaster.processing.dynamics.leveler.LevelerCalibrationProfile;
import com.quickmaster.processing.dynamics.leveler.LoudnessConformanceGuard;
import com.quickmaster.processing.dynamics.leveler.TruePeakSafety;
import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.ControlState;
import com.quickmaster.processing.dynamics.leveler.model.RampPlan;
import com.quickmaster.processing.dynamics.leveler.model.SafetyResult;
import com.quickmaster.processing.dynamics.leveler.model.SafetyStatus;
import com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisSnapshot;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Offline structural level matching between confidently comparable musical sections.
 * Analysis is control-independent and immutable. Only a current build-bound,
 * ramp-allocated and finite true-peak-proved schedule can affect audio.
 * Intro/outro/break and other protected regions never receive anticipatory gain.
 */
public final class LevelerProcessor extends AnalysisDynamicsProcessor
{
    public static final double DEFAULT_LEVELING = 0.5;
    public static final double MIN_LEVELING = 0.0;
    public static final double MAX_LEVELING = 1.0;
    public static final double DEFAULT_SPEED = 0.5;
    public static final double MIN_SPEED = 0.0;
    public static final double MAX_SPEED = 1.0;
    /** Retained API constant; duration alone is not evidence of comparability. */
    public static final double MIN_SECTION_SEC = 8.0;

    // The historical diagnostic API remains readable, but this immutable analysis
    // now also supplies the active planner. It never retains input PCM or gain.
    private volatile ShadowAnalysisSnapshot shadowAnalysis;
    private volatile double leveling = DEFAULT_LEVELING;
    private volatile double speed = DEFAULT_SPEED;

    public double getLeveling() { return leveling; }
    public double getSpeed() { return speed; }
    public String getAnalysisDiagnostic() { return publishedGain().status().name(); }
    public ShadowAnalysisSnapshot getShadowAnalysis() { return shadowAnalysis; }

    public synchronized void setLeveling(double value)
    {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Leveling must be finite.");
        double next = Math.max(MIN_LEVELING, Math.min(MAX_LEVELING, value));
        if (next != leveling)
        {
            leveling = next;
            publishUnit(AnalysisStatus.UNIT);
        }
    }

    public synchronized void setSpeed(double value)
    {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Speed must be finite.");
        double next = Math.max(MIN_SPEED, Math.min(MAX_SPEED, value));
        if (next != speed)
        {
            speed = next;
            publishUnit(AnalysisStatus.UNIT);
        }
    }

    /** Informational section median only; correction references come from comparable cohorts. */
    public double getMedianLufs()
    {
        ShadowAnalysisSnapshot snapshot = shadowAnalysis;
        if (snapshot == null) return -55;
        double[] values = new double[snapshot.cache().descriptors().size()];
        int count = 0;
        for (int i = 0; i < values.length; i++)
        {
            var loudness = snapshot.cache().descriptors().get(i).regionalLoudness();
            if (loudness.present()) values[count++] = loudness.lufs();
        }
        if (count == 0) return -55;
        Arrays.sort(values, 0, count);
        return values[(count - 1) / 2];
    }

    /** Share only immutable control-independent analysis; analyze revalidates the actual input bits. */
    public synchronized LevelerProcessor forkForAnalysis(double nextLeveling, double nextSpeed)
    {
        LevelerProcessor fork = new LevelerProcessor();
        fork.setLeveling(nextLeveling);
        fork.setSpeed(nextSpeed);
        fork.setEnabled(isEnabled());
        fork.shadowAnalysis = shadowAnalysis;
        return fork;
    }

    @Override
    public synchronized void analyze(float[] samples, int channels)
    {
        analyze(samples, channels, new CancellationToken());
    }

    /** Poll-only cancellation for an offline caller; the token is not retained. */
    public synchronized void analyze(float[] samples, int channels, CancellationToken token)
    {
        CancellationToken cancellation = token == null ? new CancellationToken() : token;
        if (cancellation.isCancelled())
        {
            shadowAnalysis = null;
            publishUnit(AnalysisStatus.CANCELLED);
            return;
        }
        if (samples == null || samples.length == 0 || (channels != 1 && channels != 2)
                || samples.length % channels != 0 || analysisRateHz() <= 0)
        {
            shadowAnalysis = null;
            publishUnit(AnalysisStatus.INVALID_INPUT);
            return;
        }
        try
        {
            AudioFormat format = new AudioFormat(analysisRateHz(), channels, samples.length / channels);
            byte[] fingerprint = fingerprint(samples, channels, cancellation);
            if (fingerprint == null)
            {
                shadowAnalysis = null;
                publishUnit(AnalysisStatus.CANCELLED);
                return;
            }
            ShadowAnalysisSnapshot next = shadowAnalysis;
            if (!matches(next, format, fingerprint))
                next = new LevelerAnalysisEngine().analyzeShadow(samples, format, cancellation);
            shadowAnalysis = next;
            if (next == null || cancellation.isCancelled())
            {
                publishUnit(cancellation.isCancelled() ? AnalysisStatus.CANCELLED : AnalysisStatus.INSUFFICIENT_ANALYSIS);
                return;
            }
            if (leveling == 0)
            {
                publishUnit(AnalysisStatus.UNIT);
                return;
            }
            if (!new LoudnessConformanceGuard().authorizesCurrentBuild(next.diagnostics().standardValidation()))
            {
                publishUnit(AnalysisStatus.STANDARD_VALIDATION_FAILED);
                return;
            }
            RampPlan plan = new GainPlanner().plan(next.cache(), new ControlState(leveling, speed));
            SafetyResult safe = new TruePeakSafety().constrain(samples, format, plan, cancellation);
            if (cancellation.isCancelled())
                publishUnit(AnalysisStatus.CANCELLED);
            else if (safe != null && safe.proof().status() == SafetyStatus.INFEASIBLE_INPUT_BASELINE)
                publishUnit(AnalysisStatus.INFEASIBLE_INPUT_BASELINE);
            else if (safe == null || !safe.proof().proven())
                publishUnit(AnalysisStatus.PEAK_UNSAFE);
            else
                publishStructural(safe.schedule(), channels);
        }
        catch (IllegalArgumentException | IllegalStateException | ArithmeticException ex)
        {
            shadowAnalysis = null;
            publishUnit(AnalysisStatus.INVALID_INPUT);
        }
    }

    @Override
    public void adoptEnvelope(AnalysisDynamicsProcessor source)
    {
        if (!(source instanceof LevelerProcessor other))
            throw new IllegalArgumentException("Leveler can only adopt a structural Leveler analysis.");
        PublishedGain publication;
        ShadowAnalysisSnapshot analysis;
        double sourceLeveling, sourceSpeed;
        // Capture without holding this lock: reciprocal adoption cannot deadlock.
        synchronized (other)
        {
            publication = other.publishedGain();
            analysis = other.shadowAnalysis;
            sourceLeveling = other.leveling;
            sourceSpeed = other.speed;
        }
        synchronized (this)
        {
            if (leveling != sourceLeveling || speed != sourceSpeed) return;
            shadowAnalysis = analysis;
            adoptPublication(publication);
        }
    }

    @Override
    synchronized void clearAnalysis()
    {
        shadowAnalysis = null;
        super.clearAnalysis();
    }

    @Override
    protected void computeFeatures(float[] samples, int channels, int sampleRate, int frames)
    {
        throw new IllegalStateException("Structural Leveler does not use the legacy dense mapper.");
    }

    @Override
    protected void mapFeaturesToGain()
    {
        throw new IllegalStateException("Structural Leveler does not allocate a per-frame gain envelope.");
    }

    private static boolean matches(ShadowAnalysisSnapshot snapshot, AudioFormat format, byte[] fingerprint)
    {
        if (snapshot == null) return false;
        var cache = snapshot.cache();
        return cache.format().sampleRateHz() == format.sampleRateHz()
                && cache.format().channels() == format.channels()
                && cache.format().frames() == format.frames()
                && cache.algorithmId().equals(LevelerAnalysisEngine.ALGORITHM_ID)
                && cache.profileId().equals(LevelerCalibrationProfile.V2.profileId())
                && cache.comparison() != null
                && cache.comparison().format() == cache.format()
                && Arrays.equals(cache.copyPcmFingerprintSha256(), fingerprint);
    }

    private static byte[] fingerprint(float[] pcm, int channels, CancellationToken token)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[8192];
            int used = 0;
            for (int i = 0; i < pcm.length; i++)
            {
                if (i % (4096 * channels) == 0 && token.isCancelled()) return null;
                if (!Float.isFinite(pcm[i])) throw new IllegalArgumentException("Non-finite Leveler input.");
                int bits = Float.floatToRawIntBits(pcm[i]);
                bytes[used++] = (byte) (bits >>> 24);
                bytes[used++] = (byte) (bits >>> 16);
                bytes[used++] = (byte) (bits >>> 8);
                bytes[used++] = (byte) bits;
                if (used == bytes.length) { digest.update(bytes); used = 0; }
            }
            digest.update(bytes, 0, used);
            return token.isCancelled() ? null : digest.digest();
        }
        catch (NoSuchAlgorithmException ex)
        {
            throw new IllegalStateException("SHA-256 is required for analysis identity.", ex);
        }
    }
}
