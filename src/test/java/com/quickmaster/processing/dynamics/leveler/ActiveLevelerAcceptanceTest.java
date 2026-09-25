package com.quickmaster.processing.dynamics.leveler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;

/** All twelve positive truth variants must affect real audio through the authentic current own-JAR. */
class ActiveLevelerAcceptanceTest {
    @TestFactory Stream<DynamicTest> protectedAndAntiphaseVariantsUseActualBoundRendering() {
        List<DynamicTest> cases = new ArrayList<>();
        for (var test : MusicalPcmFixture.catalog()) {
            if (!test.key().startsWith("N") && !test.key().equals("A07_ANTIPHASE_RATE")) continue;
            int[] rates = test.key().startsWith("N") ? new int[] {44100, 48000} : new int[] {44100, 48000, 96000};
            for (int rate : rates) for (int channels : new int[] {1, 2})
                cases.add(DynamicTest.dynamicTest(test.key() + "_bound_" + rate + "_" + channels, () -> {
                    var clip = MusicalPcmFixture.generate(test, rate, channels);
                    try (var processor = RuntimeLevelerAcceptanceTest.processor()) {
                        processor.controls(1, .5); processor.analyze(clip.pcm(), rate, channels);
                        assertEquals("PASSED", processor.conformance());
                        assertEquals("STRUCTURAL_READY", processor.status());
                        if (test.key().startsWith("N")) {
                            assertProtected(clip, test.first(), processor);
                            if (test.key().equals("N08_GAIN_SCALED_INTENT")) assertProtected(clip, test.second(), processor);
                        } else verifySound(clip, processor);
                        System.out.println("ACTIVE_PROTECTION key=" + test.key() + " rate=" + rate
                                + " channels=" + channels + " actualBound=true rawProtectedExact=true");
                    }
                }));
        }
        assertEquals(50, cases.size());
        return cases.stream();
    }
    @TestFactory Stream<DynamicTest> allPositiveRatesAndLayoutsRenderRealGain() {
        List<DynamicTest> cases = new ArrayList<>();
        for (var test : MusicalPcmFixture.catalog()) if (test.key().startsWith("P"))
            for (int rate : new int[] {44100, 48000}) for (int channels : new int[] {1, 2})
                cases.add(DynamicTest.dynamicTest(test.key() + "_" + rate + "_" + channels,
                        () -> verify(test, rate, channels)));
        assertEquals(12, cases.size());
        return cases.stream();
    }
    private static void verify(MusicalPcmFixture.Case test, int rate, int channels) throws Exception {
        var clip = MusicalPcmFixture.generate(test, rate, channels);
        try (var processor = RuntimeLevelerAcceptanceTest.processor()) {
            processor.controls(1, .5);
            processor.analyze(clip.pcm(), rate, channels);
            assertEquals("PASSED", processor.conformance());
            assertEquals("STRUCTURAL_READY", processor.status());
            double first = interiorGain(clip, test.first(), processor);
            double second = interiorGain(clip, test.second(), processor);
            if (test.key().equals("P01_ABA_LEVEL")) {
                assertTrue(first < -.8, "First comparable chorus must actually be cut");
                assertTrue(second > .8, "Second comparable chorus must actually be boosted");
                assertTrue(second - first >= 1.6, "Rendered interior loudness gap must shrink");
            } else {
                assertEquals(0, first, 1e-6, "Normal first repeat stays within its reference deadband");
                assertTrue(second > .8 && second <= 3.000001, "Quiet outlier must receive a bounded audible boost");
                assertEquals(0, interiorGain(clip, test.third(), processor), 1e-6,
                        "Normal final repeat stays within its reference deadband");
            }
            for (var edge : List.of(clip.truth().get(0), clip.truth().get(clip.truth().size() - 1))) {
                float[] input = Arrays.copyOfRange(clip.pcm(), Math.toIntExact(edge.start() * channels), Math.toIntExact(edge.end() * channels));
                float[] output = processor.render(input, channels, edge.start());
                for (int i = 0; i < input.length; i++)
                    if (Float.floatToRawIntBits(input[i]) != Float.floatToRawIntBits(output[i]))
                        fail("Protected edge changed: " + edge.label() + " sample " + i);
            }
            verifySound(clip, processor);
            System.out.println("ACTIVE_POSITIVE key=" + test.key() + " rate=" + rate + " channels=" + channels
                    + " firstGainDb=" + first + " secondGainDb=" + second + " protectedEdgesRawExact=true");
        }
    }
    private static void assertProtected(MusicalPcmFixture.Clip clip, String label,
                                        RuntimeLevelerAcceptanceTest.BoundProcessor processor) throws Exception {
        var region = clip.truth().stream().filter(t -> t.label().equals(label)).findFirst().orElseThrow();
        int channels = clip.format().channels();
        float[] input = Arrays.copyOfRange(clip.pcm(), Math.toIntExact(region.start() * channels), Math.toIntExact(region.end() * channels));
        float[] output = processor.render(input, channels, region.start());
        for (int i = 0; i < input.length; i++)
            if (Float.floatToRawIntBits(input[i]) != Float.floatToRawIntBits(output[i]))
                fail("Protected/ambiguous truth changed: " + clip.testCase().key() + "/" + label + " sample " + i);
    }
    private static void verifySound(MusicalPcmFixture.Clip clip,
                                    RuntimeLevelerAcceptanceTest.BoundProcessor processor) throws Exception {
        var pieces = processor.pieces();
        assertTrue(pieces.size() <= 258);
        double previousEnd = 0, previousDb = 0, maxSlope = 0;
        int rate = clip.format().sampleRateHz(), channels = clip.format().channels();
        for (var piece : pieces) {
            assertTrue(piece.start() >= previousEnd && piece.end() > piece.start() && piece.end() <= clip.format().frames());
            assertTrue(piece.fromDb() >= -6 && piece.fromDb() <= 3 && piece.toDb() >= -6 && piece.toDb() <= 3);
            if (piece.start() > previousEnd) {
                assertEquals(0, previousDb, 1e-12, "No gain jump into an uncovered gap");
                assertEquals(0, piece.fromDb(), 1e-12, "No gain jump out of an uncovered gap");
            } else assertEquals(previousDb, piece.fromDb(), 1e-12, "Adjacent gain pieces are continuous");
            if (!piece.smooth()) assertEquals(piece.fromDb(), piece.toDb());
            double slope = piece.smooth() ? 1.5 * Math.abs(piece.toDb() - piece.fromDb()) * rate / (piece.end() - piece.start()) : 0;
            assertTrue(slope <= 2 + 1e-12, "Analytic maximum slope exceeds 2 dB/s: " + slope);
            maxSlope = Math.max(maxSlope, slope);
            // Both HOLD and smoothstep derivatives are analytically zero at either endpoint.
            previousEnd = piece.end(); previousDb = piece.toDb();
        }
        assertEquals(0, previousDb, 1e-12, "No gain jump at schedule EOF");
        float[] output = processor.render(clip.pcm(), channels, 0);
        int pieceIndex = 0; long rawUnit = 0; double maxError = 0, peak = 0;
        for (int frame = 0; frame < clip.format().frames(); frame++) {
            while (pieceIndex < pieces.size() && frame >= pieces.get(pieceIndex).end()) pieceIndex++;
            double db = 0;
            if (pieceIndex < pieces.size()) {
                var piece = pieces.get(pieceIndex);
                if (frame >= piece.start()) {
                    double u = (frame - piece.start()) / (piece.end() - piece.start());
                    db = piece.smooth() ? piece.fromDb() + (piece.toDb() - piece.fromDb()) * (u * u) * (3 - 2 * u) : piece.fromDb();
                }
            }
            double scalar = StrictMath.exp(db * StrictMath.log(10.0) / 20.0);
            for (int channel = 0; channel < channels; channel++) {
                int i = frame * channels + channel;
                float expected = db == 0 ? clip.pcm()[i] : (float)(clip.pcm()[i] * scalar);
                if (!Float.isFinite(output[i])) fail("Nonfinite rendered sample " + i);
                double error = Math.abs((double)expected - output[i]);
                if (db == 0) {
                    if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(output[i])) fail("Unit sample changed " + i);
                    rawUnit++;
                } else if (error > Math.ulp(expected)) fail("Output is not the scheduled common scalar at original frame " + frame + " channel " + channel);
                maxError = Math.max(maxError, error); peak = Math.max(peak, Math.abs(output[i]));
            }
        }
        assertTrue(peak <= 1, "Positive/antiphase fixture clips");
        processor.enabled(false);
        float[] bypass = processor.render(clip.pcm(), channels, 0);
        for (int i = 0; i < bypass.length; i++)
            if (Float.floatToRawIntBits(bypass[i]) != Float.floatToRawIntBits(clip.pcm()[i])) fail("Disabled bypass changed " + i);
        processor.enabled(true);
        System.out.println("ACTIVE_SOUND key=" + clip.testCase().key() + " rate=" + rate + " channels=" + channels
                + " samples=" + output.length + " pieces=" + pieces.size() + " rawUnitSamples=" + rawUnit
                + " maxScalarError=" + maxError + " maxSlopeDbPerSecond=" + maxSlope + " samplePeak=" + peak
                + " bypassRawExact=true humanListening=false");
    }
    private static double interiorGain(MusicalPcmFixture.Clip clip, String label,
                                      RuntimeLevelerAcceptanceTest.BoundProcessor processor) throws Exception {
        var region = clip.truth().stream().filter(t -> t.label().equals(label)).findFirst().orElseThrow();
        int rate = clip.format().sampleRateHz(), channels = clip.format().channels();
        long start = region.start() + (region.end() - region.start()) / 2 - rate / 2;
        assertTrue(start >= region.start() && start + rate <= region.end());
        float[] input = Arrays.copyOfRange(clip.pcm(), Math.toIntExact(start * channels), Math.toIntExact((start + rate) * channels));
        float[] output = processor.render(input, channels, start);
        double before = 0, after = 0;
        for (int i = 0; i < input.length; i++) {
            assertTrue(Float.isFinite(output[i]));
            before += (double)input[i] * input[i];
            after += (double)output[i] * output[i];
        }
        assertTrue(before > 0 && after > 0);
        double observed = 10 * StrictMath.log10(after / before);
        assertEquals(processor.gainAt(start + rate / 2.0), observed, 1e-6,
                "Reported hold must agree with actual rendered PCM, not just a meter");
        return observed;
    }
}
