package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.FeatureTimeline;
import com.quickmaster.processing.dynamics.leveler.model.FrameRange;
import com.quickmaster.processing.dynamics.leveler.model.LayoutStatus;
import com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisSnapshot;
import com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisStatus;

class LevelerAnalysisEngineIntegrationTest
{
    @Test
    void finalFingerprintDoesNotCompleteAnAlreadyCancelledOperation() throws Exception
    {
        CancellationToken token = new CancellationToken();
        token.cancel();
        assertNull(fingerprint(new float[] { 0.0f, -0.0f, .125f }, token),
                "QM_LEVELER_TERMINAL_FINGERPRINT_CANCEL: terminal work must honor its token");
    }

    @Test
    void cancellableFingerprintPreservesRawBitsAndTheIncompleteChunk() throws Exception
    {
        float[] pcm = new float[10_003];
        ByteBuffer expected = ByteBuffer.allocate(pcm.length * Float.BYTES);
        for (int i = 0; i < pcm.length; i++)
        {
            pcm[i] = switch (i % 5) {
                case 0 -> 0.0f;
                case 1 -> -0.0f;
                case 2 -> Float.intBitsToFloat(1 + i);
                case 3 -> -Float.intBitsToFloat(1 + i);
                default -> i / 16_384.0f;
            };
            expected.putInt(Float.floatToRawIntBits(pcm[i]));
        }
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected.array()),
                fingerprint(pcm, new CancellationToken()));
    }

    @Test
    void engineUsesCancellableValidationAndStillRejectsInvalidPcm() throws Exception
    {
        String source = Files.readString(Path.of(
                "src/main/java/com/quickmaster/processing/dynamics/leveler/LevelerAnalysisEngine.java"));
        assertFalse(source.contains("source.validatePcm(pcm)"),
                "QM_LEVELER_UNCANCELLABLE_DUPLICATE_PREFLIGHT");
        assertTrue(source.contains("new LoudnessAnalyzer().analyze(pcm, source, token)"));
        AudioFormat format = new AudioFormat(48_000, 2, 24_000);
        for (float invalid : new float[] { Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY })
        {
            float[] pcm = new float[48_000];
            pcm[pcm.length - 1] = invalid;
            assertNull(new LevelerAnalysisEngine().analyzeShadow(pcm, format, new CancellationToken()));
        }
        assertNull(new LevelerAnalysisEngine().analyzeShadow(new float[47_999], format, new CancellationToken()));
        CancellationToken token = new CancellationToken();
        token.cancel();
        assertNull(new LevelerAnalysisEngine().analyzeShadow(new float[48_000], format, token));
    }

    // The legacy signature is deliberately exercised for a causal baseline comparison.
    private static byte[] fingerprint(float[] pcm, CancellationToken token) throws Exception
    {
        Method method;
        try { method = LevelerAnalysisEngine.class.getDeclaredMethod("fingerprint", float[].class, CancellationToken.class); }
        catch (NoSuchMethodException legacy) {
            method = LevelerAnalysisEngine.class.getDeclaredMethod("fingerprint", float[].class);
            method.setAccessible(true);
            return (byte[]) method.invoke(null, (Object) pcm);
        }
        method.setAccessible(true);
        return (byte[]) method.invoke(null, pcm, token);
    }

    @ParameterizedTest
    @ValueSource(ints = { 47_999, 48_000 })
    void completeEngineKeepsExactExtentDespiteIdenticalFeatureCenters(int frames)
    {
        AudioFormat source = new AudioFormat(48_000, 1, frames);
        ShadowAnalysisSnapshot snapshot = new LevelerAnalysisEngine().analyzeShadow(
                new float[frames], source, new CancellationToken());

        assertNotNull(snapshot, "Complete engine must accept the exact source extent " + frames);
        assertSame(source, snapshot.cache().format());
        assertEquals(ShadowAnalysisStatus.DONE, snapshot.result().status());
        assertEquals(ShadowAnalysisStatus.DONE, snapshot.diagnostics().status());
        assertEquals(0x1FFL, snapshot.diagnostics().completedPhaseBits());
        FeatureTimeline features = snapshot.cache().features();
        assertEquals(24_000L, features.hopFrames());
        assertEquals(2, features.size());
        assertEquals(11_999L, features.frame(0).centerFrame());
        assertEquals(35_999L, features.frame(1).centerFrame());
        assertEquals(LayoutStatus.READY, snapshot.cache().layout().status());
        long end = 0L;
        for (int i = 0; i < snapshot.cache().layout().regions().size(); i++)
        {
            FrameRange range = snapshot.cache().layout().regions().get(i);
            assertEquals(end, range.startInclusive());
            end = range.endExclusive();
        }
        assertEquals(source.frames(), end);
    }
}
