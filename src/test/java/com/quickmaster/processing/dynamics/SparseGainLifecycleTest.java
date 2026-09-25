package com.quickmaster.processing.dynamics;

import com.quickmaster.processing.dynamics.leveler.model.GainPiece;
import com.quickmaster.processing.dynamics.leveler.model.GainPieceShape;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SparseGainLifecycleTest {
    private static final class Stage extends AnalysisDynamicsProcessor {
        @Override protected void computeFeatures(float[] samples, int channels, int rate, int frames) { }
        @Override protected void mapFeaturesToGain() { throw new AssertionError("Sparse must not call Dense mapping"); }
        void install(SparseGainSchedule schedule, int channels) { publishStructural(schedule, channels); }
    }
    private static SparseGainSchedule schedule(int rate) {
        return new SparseGainSchedule(rate, 20, new GainPiece[]{
                new GainPiece(2, 6, 0, -2, GainPieceShape.SMOOTHSTEP),
                new GainPiece(6, 10, -2, -2, GainPieceShape.HOLD),
                new GainPiece(10, 14, -2, 0, GainPieceShape.SMOOTHSTEP)});
    }
    private static Stage stage(SparseGainSchedule schedule, int channels, int preparedRate) {
        Stage stage = new Stage();
        stage.setEnabled(true);
        stage.prepare(preparedRate, schedule.sourceFrames() * channels);
        stage.install(schedule, channels);
        return stage;
    }
    private static int bits(float value) { return Float.floatToRawIntBits(value); }

    @Test void representedPositionsAtOneTwoAndFourTimesRateAreBitIdentical() {
        SparseGainSchedule schedule = schedule(48_000);
        for (int factor : new int[]{1, 2, 4}) {
            Stage stage = stage(schedule, 2, factor * 48_000);
            for (int sourceFrame = -2; sourceFrame <= 21; sourceFrame++) {
                stage.setPlaybackPosition((long) sourceFrame * factor);
                float[] frame = {0x1.7p-1f, -0x1.3p-2f};
                double db = schedule.gainDbAt(sourceFrame);
                float left = db == 0 ? frame[0] : (float) (frame[0] * StrictMath.exp(db * StrictMath.log(10.0) / 20));
                float right = db == 0 ? frame[1] : (float) (frame[1] * StrictMath.exp(db * StrictMath.log(10.0) / 20));
                stage.process(frame, 2);
                assertEquals(bits(left), bits(frame[0]));
                assertEquals(bits(right), bits(frame[1]));
                assertEquals(Double.doubleToRawLongBits(db), Double.doubleToRawLongBits(stage.getGainReductionDb()));
            }
        }
    }

    @Test void blockPartitionAndPreparePreserveTheCompletePublication() {
        SparseGainSchedule schedule = schedule(48_000);
        Stage single = stage(schedule, 2, 192_000), chunks = stage(schedule, 2, 192_000);
        float[] expected = new float[176], actual = new float[176];
        for (int i = 0; i < expected.length; i++) expected[i] = actual[i] = (float) StrictMath.sin(i * .31);
        single.setPlaybackPosition(-4);
        chunks.setPlaybackPosition(-4);
        single.process(expected, 2);
        int[] lengths = {2, 14, 6, 38, 4, 62, 50};
        int offset = 0;
        for (int length : lengths) {
            float[] block = Arrays.copyOfRange(actual, offset, offset + length);
            chunks.process(block, 2);
            System.arraycopy(block, 0, actual, offset, length);
            offset += length;
        }
        assertEquals(actual.length, offset);
        for (int i = 0; i < actual.length; i++) assertEquals(bits(expected[i]), bits(actual[i]));
        PublishedGain publication = single.publishedGain();
        single.prepare(48_000, 40);
        assertSame(publication, single.publishedGain());
        assertTrue(single.isAnalyzed());
    }

    @Test void adoptionFromTwoEquallyNumberedForksCannotReuseSparsePieceIndex() {
        Stage first = stage(schedule(48_000), 1, 48_000);
        Stage second = stage(new SparseGainSchedule(48_000, 20,
                new GainPiece[]{new GainPiece(0, 20, 1, 1, GainPieceShape.HOLD)}), 1, 48_000);
        assertEquals(first.publishedGain().analysisGeneration(), second.publishedGain().analysisGeneration());
        Stage destination = new Stage();
        destination.setEnabled(true); destination.prepare(48_000, 20);
        destination.adoptEnvelope(first);
        destination.setPlaybackPosition(11);
        destination.process(new float[]{1}, 1); // primes the cursor at piece 2
        long generation = destination.publishedGain().analysisGeneration();
        destination.adoptEnvelope(second);
        assertTrue(destination.publishedGain().analysisGeneration() > generation);
        float[] next = {1}; destination.process(next, 1);
        assertEquals(bits((float) StrictMath.exp(StrictMath.log(10.0) / 20)), bits(next[0]));
    }

    @Test void unitInvalidAndDisabledPathsPreserveEveryBitAndClearMeter() {
        Stage stage = stage(schedule(48_000), 2, 48_000);
        float[] values = {-0.0f, 0.0f, Float.MIN_VALUE, -Float.MIN_VALUE};
        float[] original = values.clone();
        stage.setPlaybackPosition(-2); stage.process(values, 2);
        for (int i = 0; i < values.length; i++) assertEquals(bits(original[i]), bits(values[i]));
        stage.setPlaybackPosition(7); stage.process(new float[]{1, -1}, 2);
        assertEquals(-2, stage.getGainReductionDb());
        stage.setEnabled(false); stage.process(values, 2);
        assertEquals(0, stage.getGainReductionDb());
        stage.setEnabled(true); stage.process(values, 1); // wrong source layout
        assertEquals(0, stage.getGainReductionDb());
        stage.clearAnalysis();
        assertFalse(stage.isAnalyzed());
        stage.process(values, 2);
        for (int i = 0; i < values.length; i++) assertEquals(bits(original[i]), bits(values[i]));
    }

    @Test void readyStatusCannotCrossGainDomains() {
        SparseGainSchedule sparse = schedule(48_000);
        assertThrows(IllegalArgumentException.class,
                () -> new PublishedGain(sparse, 48_000, 1, 1, AnalysisStatus.LEGACY_READY));
        assertThrows(IllegalArgumentException.class,
                () -> new PublishedGain(sparse, 44_100, 1, 1, AnalysisStatus.STRUCTURAL_READY));
        assertThrows(IllegalArgumentException.class,
                () -> PublishedGain.unit(1, AnalysisStatus.STRUCTURAL_READY));
    }
}
