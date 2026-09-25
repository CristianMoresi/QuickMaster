package com.quickmaster.processing.dynamics;

import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.AudioProcessor;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.analysis.TrackAnalysis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Original raw-bit goldens remain binding for Peak/Beat/Punch; Leveler now fails closed without its own bound package. */
@SuppressWarnings("deprecation")
class AnalysisDynamicsCompatibilityTest
{
    private static final int SAMPLE_RATE = 44_100;

    private enum Kind { PEAK, BEAT, PUNCH, LEVELER }

    private static final class InstrumentedStage implements AudioProcessor
    {
        private final Kind kind;
        private final AnalysisDynamicsProcessor delegate;
        private final MessageDigest outputDigest = sha256Digest();
        private final MessageDigest meterDigest = sha256Digest();
        private int prepareCalls;
        private int analyzeCalls;
        private int processCalls;
        private int changedBlocks;
        private String analyzeInputSha256;

        InstrumentedStage(Kind kind, AnalysisDynamicsProcessor delegate)
        {
            this.kind = kind;
            this.delegate = delegate;
        }

        @Override
        public void prepare(int sampleRate, long totalSamples)
        {
            prepareCalls++;
            delegate.prepare(sampleRate, totalSamples);
        }

        @Override
        public void analyze(float[] samples, int channels)
        {
            analyzeCalls++;
            analyzeInputSha256 = rawBitSha256(samples);
            delegate.analyze(samples, channels);
        }

        @Override
        public boolean usesAnalysis()
        {
            return delegate.usesAnalysis();
        }

        @Override
        public int getLatencyFrames()
        {
            return delegate.getLatencyFrames();
        }

        @Override
        public void setPlaybackPosition(long frame)
        {
            delegate.setPlaybackPosition(frame);
        }

        @Override
        public float[] process(float[] buffer, int channels)
        {
            processCalls++;
            int[] before = rawBits(buffer);
            float[] result = delegate.process(buffer, channels);
            if (!Arrays.equals(before, rawBits(result))) changedBlocks++;
            updateRawFloatBits(outputDigest, result);
            meterDigest.update(ByteBuffer.allocate(Long.BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(Double.doubleToRawLongBits(delegate.getGainReductionDb()))
                    .array());
            return result;
        }

        @Override
        public boolean isEnabled()
        {
            return delegate.isEnabled();
        }

        @Override
        public void setEnabled(boolean enabled)
        {
            delegate.setEnabled(enabled);
        }

        String outputSha256()
        {
            return HexFormat.of().formatHex(outputDigest.digest());
        }

        String meterSha256()
        {
            return HexFormat.of().formatHex(meterDigest.digest());
        }
    }

    @Test
    @DisplayName("Peak/Beat/Punch retain legacy raw bits; unbound structural Leveler is exactly unit")
    void allLegacyProcessorsAreRawBitExactMonoAndStereo()
    {
        for (Kind kind : Kind.values())
        {
            for (int channels : new int[] { 1, 2 })
            {
                float[] input = fixture(kind, channels);
                AnalysisDynamicsProcessor processor = processor(kind, input, channels, 1.0, .5);
                processor.prepare(SAMPLE_RATE, input.length);
                processor.analyze(input, channels);
                processor.prepare(SAMPLE_RATE, input.length);
                float[] actual = renderInBlocks(processor, input, channels,
                        new int[] { 1, 7, 257, 1_024 });
                if (kind == Kind.LEVELER)
                {
                    assertRawEquals(input, actual, "Unbound source classes cannot authorize structural gain");
                    assertEquals(AnalysisStatus.STANDARD_VALIDATION_FAILED, processor.publishedGain().status());
                    assertFalse(processor.isAnalyzed());
                }
                else
                {
                    assertEquals(individualHeadSha256(kind, channels), rawBitSha256(actual),
                            kind + " " + channels + "ch must match the raw-bit HEAD golden");
                    assertFalse(Arrays.equals(rawBits(input), rawBits(actual)),
                            kind + " fixture must exercise a non-unity envelope");
                }
            }
        }
    }

    @Test
    @DisplayName("The cumulative pipeline preserves upstream Dense goldens and the unbound Leveler veto")
    void cumulativePipelineMatchesLegacyRenderer()
    {
        for (int channels : new int[] { 1, 2 })
        {
            float[] input = cumulativeFixture(channels);
            ProcessingPipeline pipeline = new ProcessingPipeline();
            List<InstrumentedStage> stages = new ArrayList<>();
            for (Kind kind : Kind.values())
            {
                InstrumentedStage stage = new InstrumentedStage(
                        kind, processor(kind, input, channels, 1.0, .5));
                stages.add(stage);
                pipeline.addProcessor(stage);
            }

            assertEquals(4, pipeline.getProcessors().size());
            for (int i = 0; i < stages.size(); i++)
            {
                assertSame(stages.get(i), pipeline.getProcessors().get(i),
                        "the product order must remain Peak -> Beat -> Punch -> Leveler");
                assertEquals(Kind.values()[i], stages.get(i).kind);
            }

            WavFile file = new WavFile("compat.wav", SAMPLE_RATE, channels,
                    input.clone(), 16, false);
            pipeline.process(file);

            assertEquals(pipelineStageInputHeadSha256(channels, Kind.LEVELER), rawBitSha256(file.getSamples()),
                    "Unbound Leveler must preserve the independently pinned Punch output");
            String previousOutput = rawBitSha256(input);
            for (InstrumentedStage stage : stages)
            {
                String stageOutput = stage.outputSha256();
                assertEquals(3, stage.prepareCalls, stage.kind + " prepare participation");
                assertEquals(1, stage.analyzeCalls, stage.kind + " analyze participation");
                assertEquals(862, stage.processCalls, stage.kind + " block participation");
                assertEquals(previousOutput, stage.analyzeInputSha256,
                        stage.kind + " must analyze the accumulated upstream output");
                assertEquals(pipelineStageInputHeadSha256(channels, stage.kind),
                        stage.analyzeInputSha256, stage.kind + " HEAD input golden");
                if (stage.kind == Kind.LEVELER)
                {
                    assertEquals(0, stage.changedBlocks);
                    assertEquals(stage.analyzeInputSha256, stageOutput);
                    MessageDigest zeroMeter = sha256Digest();
                    zeroMeter.update(new byte[862 * Long.BYTES]);
                    assertEquals(HexFormat.of().formatHex(zeroMeter.digest()), stage.meterSha256());
                    assertEquals(AnalysisStatus.STANDARD_VALIDATION_FAILED, stage.delegate.publishedGain().status());
                }
                else
                {
                    assertTrue(stage.changedBlocks > 0, stage.kind + " must alter this fixture");
                    assertEquals(pipelineStageOutputHeadSha256(channels, stage.kind),
                            stageOutput, stage.kind + " HEAD output golden");
                    assertEquals(pipelineStageMeterHeadSha256(channels, stage.kind),
                            stage.meterSha256(), stage.kind + " HEAD meter golden");
                }
                previousOutput = stageOutput;
            }
            assertEquals(rawBitSha256(file.getSamples()), previousOutput,
                    "the Leveler stage must be the pipeline's final output");
        }
    }

    @Test
    @DisplayName("Leveler control extremes cannot bypass current-build authorization")
    void levelerControlExtremesRemainLegacy()
    {
        float[] input = fixture(Kind.LEVELER, 2);
        for (double leveling : new double[] { 0.0, 1.0 })
        {
            for (double speed : new double[] { 0.0, 1.0 })
            {
                LevelerProcessor processor = (LevelerProcessor) processor(
                        Kind.LEVELER, input, 2, leveling, speed);
                processor.prepare(SAMPLE_RATE, input.length);
                processor.analyze(input, 2);
                processor.prepare(SAMPLE_RATE, input.length);
                float[] actual = renderInBlocks(processor, input, 2, new int[] { 17, 1_024 });
                assertRawEquals(input, actual, "Unbound Leveler control extremes are exact bypass");
                assertEquals(leveling == 0.0 ? AnalysisStatus.UNIT : AnalysisStatus.STANDARD_VALIDATION_FAILED,
                        processor.publishedGain().status());
            }
        }

    }

    private static AnalysisDynamicsProcessor processor(Kind kind, float[] input, int channels,
                                                       double leveling, double speed)
    {
        TrackAnalysis analysis = new TrackAnalysis();
        analysis.analyze(input, channels, SAMPLE_RATE);
        analysis.setManualBpm(120.0);
        return switch (kind)
        {
            case PEAK ->
            {
                PeakCompProcessor p = new PeakCompProcessor();
                p.setTrackAnalysis(analysis);
                p.setTargetDb(-6.0);
                p.setEnabled(true);
                yield p;
            }
            case BEAT ->
            {
                BeatCompProcessor p = new BeatCompProcessor();
                p.setTrackAnalysis(analysis);
                p.setTargetDb(-6.0);
                p.setNote(BeatCompProcessor.NoteValue.QUARTER);
                p.setEnabled(true);
                yield p;
            }
            case PUNCH ->
            {
                PunchProcessor p = new PunchProcessor();
                p.setTrackAnalysis(analysis);
                p.setAmountDb(6.0);
                p.setEnabled(true);
                yield p;
            }
            case LEVELER ->
            {
                LevelerProcessor p = new LevelerProcessor();
                p.setLeveling(leveling);
                p.setSpeed(speed);
                p.setEnabled(true);
                yield p;
            }
        };
    }

    private static float[] fixture(Kind kind, int channels)
    {
        float[] mono = (kind == Kind.LEVELER) ? levelerFixture() : transientFixture();
        if (channels == 1) return mono;
        float[] stereo = new float[mono.length * 2];
        for (int frame = 0; frame < mono.length; frame++)
        {
            stereo[frame * 2] = mono[frame];
            stereo[frame * 2 + 1] = mono[frame] * .73f;
        }
        return stereo;
    }

    private static float[] transientFixture()
    {
        int frames = SAMPLE_RATE * 6;
        float[] samples = new float[frames];
        double phaseStep = 2.0 * Math.PI * 220.0 / SAMPLE_RATE;
        for (int i = 0; i < frames; i++)
        {
            samples[i] = (float) (.22 * Math.sin(phaseStep * i));
        }
        Random random = new Random(0x4d303033L);
        int burstFrames = (int) (.012 * SAMPLE_RATE);
        int onset = (int) (.2 * SAMPLE_RATE);
        for (int k = 0; onset < frames; k++, onset += SAMPLE_RATE / 2)
        {
            double amplitude = (k % 4 == 1) ? .92 : .48;
            for (int i = 0; i < burstFrames && onset + i < frames; i++)
            {
                double decay = Math.exp(-i / (.003 * SAMPLE_RATE));
                samples[onset + i] += (float) ((random.nextDouble() * 2.0 - 1.0)
                        * amplitude * decay);
            }
        }
        return samples;
    }

    private static float[] levelerFixture()
    {
        int frames = SAMPLE_RATE * 20;
        int boundary = SAMPLE_RATE * 6;
        float[] samples = new float[frames];
        double phaseStep = 2.0 * Math.PI * 220.0 / SAMPLE_RATE;
        for (int i = 0; i < frames; i++)
        {
            double amplitude = (i < boundary) ? .8 : .22;
            samples[i] = (float) (amplitude * Math.sin(phaseStep * i));
        }
        return samples;
    }

    private static float[] cumulativeFixture(int channels)
    {
        int frames = SAMPLE_RATE * 20;
        float[] mono = new float[frames];
        Random random = new Random(0x5042504cL);
        double phaseStep = 2.0 * Math.PI * 220.0 / SAMPLE_RATE;
        for (int frame = 0; frame < frames; frame++)
        {
            double seconds = frame / (double) SAMPLE_RATE;
            double amplitude = seconds < 6.0 ? .72 : seconds < 14.0 ? .24 : .52;
            mono[frame] = (float) (amplitude * Math.sin(phaseStep * frame));
        }
        int burstFrames = (int) (.012 * SAMPLE_RATE);
        for (int onset = SAMPLE_RATE / 5, k = 0; onset < frames;
             onset += SAMPLE_RATE / 2, k++)
        {
            double burstAmplitude = (k % 4 == 1) ? .24 : .12;
            for (int i = 0; i < burstFrames && onset + i < frames; i++)
            {
                double decay = Math.exp(-i / (.003 * SAMPLE_RATE));
                mono[onset + i] += (float) ((random.nextDouble() * 2.0 - 1.0)
                        * burstAmplitude * decay);
            }
        }
        if (channels == 1) return mono;
        float[] stereo = new float[mono.length * 2];
        for (int frame = 0; frame < mono.length; frame++)
        {
            stereo[frame * 2] = mono[frame];
            stereo[frame * 2 + 1] = mono[frame] * .73f;
        }
        return stereo;
    }

    private static float[] renderInBlocks(AnalysisDynamicsProcessor processor,
                                          float[] input,
                                          int channels,
                                          int[] blockPattern)
    {
        int totalFrames = input.length / channels;
        float[] output = new float[input.length];
        int frame = 0;
        int blockIndex = 0;
        while (frame < totalFrames)
        {
            int frames = Math.min(blockPattern[blockIndex++ % blockPattern.length], totalFrames - frame);
            float[] block = Arrays.copyOfRange(input, frame * channels, (frame + frames) * channels);
            processor.process(block, channels);
            System.arraycopy(block, 0, output, frame * channels, block.length);
            frame += frames;
        }
        return output;
    }

    private static String individualHeadSha256(Kind kind, int channels)
    {
        return switch (kind)
        {
            case PEAK -> channels == 1
                    ? "f9f0cd4efcdb0d5d9aa23a64e85cbf7ac0f3d364a6c1727f7071606715026464"
                    : "856a4b2769130c37257e70ed880dabcc9464dab8a3b3edfec5ed22ff8957fc56";
            case BEAT -> channels == 1
                    ? "ad1c931d3d7fa994a0dcd80204d363900b79a1317983f5bb2816c7c0b505c237"
                    : "3a44495f9d9f41f99e8d8ca06dddba712b3308e12827eb481851b5ca04fa04df";
            case PUNCH -> channels == 1
                    ? "893b70fb4f7aea62f23d86d15f3bae5fdc998488dd86d46393e7c60c687d3a77"
                    : "d03d6329d565b2ec118409bb72b1ee1bbefd279cb605f589c3ad13d250515593";
            case LEVELER -> channels == 1
                    ? "446736f8c31e950b70296cb0b9180a2843a53fade35c9eb48e14c5dbed420efc"
                    : "bcbdbf36789a1cdcc2f932471e4ae7cdb4d995abdf6f30a872030ac76239fab1";
        };
    }

    private static String levelerHeadSha256(double leveling, double speed)
    {
        if (leveling == 0.0)
        {
            return "b377a8665a03b6331e463fc1afa02a6d4ccc0a53a11936ff4bfd2f650f163945";
        }
        return speed == 0.0
                ? "76d1df23cb0eacedd4999367fc78272b8dc5622bf7507a5a5efea6e046fcc7c8"
                : "93150177dade368f1c8d362a6aa74739d4447cafc347cb731cc3e4bdb8ef0ef2";
    }

    private static String pipelineFinalHeadSha256(int channels)
    {
        return channels == 1
                ? "714a143e0a634a5e91a4c496c96742d97c9702fdd0afb041cd3faaa831aaa290"
                : "ed4b0c560d6a76d58a534f0df6542a3a5992c34ca089aa79d77bfbecf68800bd";
    }

    private static String pipelineStageInputHeadSha256(int channels, Kind kind)
    {
        if (channels == 1)
        {
            return switch (kind)
            {
                case PEAK -> "6825d9abad312833186a64b82a66cb9b373ac57535ce988b15675131455bd070";
                case BEAT -> "e755b8cb1aeee2f3cb01965d9e3a64102cb2b4a057d05d1e0e4c6319494c90ee";
                case PUNCH -> "30e0441440fc5d9381599ea9a3682ad32062aa72eef3ebef9e13aca56aa2054a";
                case LEVELER -> "952b87abc7841e386078efce8392cc73f373a68e4277bd3a4a7d53822911bddc";
            };
        }
        return switch (kind)
        {
            case PEAK -> "4b2caefae2b2cfc5b75b6055d24ff6219a9178925f689b6960938c4a0c35499a";
            case BEAT -> "3a3c6c1ab43cae360f99b05059281ca7ca842532b48e5c54d47865ce3efc599c";
            case PUNCH -> "865fe234ccb2bb3f9574227948cfbd33d15986798347e47dcb4a9babbd95abcd";
            case LEVELER -> "e416ae8141569dfb9180211d9b23f12f4847a75722d293c29608d1d036653b6c";
        };
    }

    private static String pipelineStageOutputHeadSha256(int channels, Kind kind)
    {
        if (channels == 1)
        {
            return switch (kind)
            {
                case PEAK -> "e755b8cb1aeee2f3cb01965d9e3a64102cb2b4a057d05d1e0e4c6319494c90ee";
                case BEAT -> "30e0441440fc5d9381599ea9a3682ad32062aa72eef3ebef9e13aca56aa2054a";
                case PUNCH -> "952b87abc7841e386078efce8392cc73f373a68e4277bd3a4a7d53822911bddc";
                case LEVELER -> "714a143e0a634a5e91a4c496c96742d97c9702fdd0afb041cd3faaa831aaa290";
            };
        }
        return switch (kind)
        {
            case PEAK -> "3a3c6c1ab43cae360f99b05059281ca7ca842532b48e5c54d47865ce3efc599c";
            case BEAT -> "865fe234ccb2bb3f9574227948cfbd33d15986798347e47dcb4a9babbd95abcd";
            case PUNCH -> "e416ae8141569dfb9180211d9b23f12f4847a75722d293c29608d1d036653b6c";
            case LEVELER -> "ed4b0c560d6a76d58a534f0df6542a3a5992c34ca089aa79d77bfbecf68800bd";
        };
    }

    private static String pipelineStageMeterHeadSha256(int channels, Kind kind)
    {
        return switch (kind)
        {
            case PEAK -> "91417167408a2867671f61b87c61f3e59cebb95152c3bb740d0a64918c425bc1";
            case BEAT -> "b2bb059e0b000b30cc7ade329602ed75dc44d4c9c0ddad093c42e1ef95c228ba";
            case PUNCH -> "3faac23b6c9e621aa500ad1818ad8dc0edac4bb886a0f54e9313ee5a4ab23df1";
            case LEVELER -> channels == 1
                    ? "64e6528e10c763f0bbe93ccf590341896b3e028c94a1c8f34ca2b8635db860aa"
                    : "2f69ad7fda56034abcc9daeab2937cba44a7b325b513e3dd0f71366f4c3493c0";
        };
    }

    private static void assertRawEquals(float[] expected, float[] actual, String context)
    {
        assertArrayEquals(rawBits(expected), rawBits(actual), context);
    }

    private static int[] rawBits(float[] values)
    {
        int[] bits = new int[values.length];
        for (int i = 0; i < values.length; i++) bits[i] = Float.floatToRawIntBits(values[i]);
        return bits;
    }

    private static MessageDigest sha256Digest()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException ex)
        {
            throw new AssertionError(ex);
        }
    }

    private static String rawBitSha256(float[] values)
    {
        MessageDigest digest = sha256Digest();
        updateRawFloatBits(digest, values);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateRawFloatBits(MessageDigest digest, float[] values)
    {
        ByteBuffer bytes = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (float value : values)
        {
            bytes.clear();
            bytes.putInt(Float.floatToRawIntBits(value));
            digest.update(bytes.array());
        }
    }
}
