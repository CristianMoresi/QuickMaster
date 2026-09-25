package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.BitSet;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.LoudnessTimeline;
import com.quickmaster.processing.dynamics.leveler.model.MeasuredLoudness;

/** Duration-differential allocation evidence; fixtures and diagnostics stay outside each measured call. */
class HannAllocationTest
{
    @Test
    void comparisonAllocationGrowthDoesNotFollowWindowSamples()
    {
        ComparisonFeatureExtractor extractor = new ComparisonFeatureExtractor();
        CancellationToken token = new CancellationToken();
        assertBoundedGrowth("comparison", fixture -> extractor.extract(fixture.pcm(), fixture.format(), token));
    }

    @Test
    void structuralAllocationGrowthDoesNotFollowWindowSamples()
    {
        StructuralFeatureExtractor extractor = new StructuralFeatureExtractor();
        CancellationToken token = new CancellationToken();
        assertBoundedGrowth("structural", fixture -> extractor.extract(
                fixture.pcm(), fixture.format(), fixture.loudness(), token));
    }

    @Test
    void restoredComparisonAndPerChannelStructuralMutantsFailTheAllocationOracle() throws Exception
    {
        String comparison = HannMutant.replaceOnce(HannMutant.source("ComparisonFeatureExtractor"),
                "time[n] = (float) (sample * hannWindow[n]);", "time[n] = (float) (sample * hann(n, support));");
        try (var mutant = HannMutant.compile("ComparisonFeatureExtractor", "HannRepeatedComparisonMutant", comparison))
        {
            CancellationToken token = new CancellationToken();
            AssertionError cause = assertThrows(AssertionError.class, () -> assertBoundedGrowth("comparison mutant",
                    fixture -> mutant.extract(fixture.pcm(), fixture.format(), token)));
            assertTrue(cause.getMessage().contains("allocation follows duration"), cause.getMessage());
            System.out.println("HANN_MUTANT_CAUSE repeated comparison: " + cause.getMessage());
        }
        String structural = HannMutant.replaceOnce(HannMutant.source("StructuralFeatureExtractor"),
                "                    for (int n = 0; n < fftSize; n++)",
                "                    hannReady = false;\n                    for (int n = 0; n < fftSize; n++)");
        try (var mutant = HannMutant.compile("StructuralFeatureExtractor", "HannResetStructuralMutant", structural))
        {
            CancellationToken token = new CancellationToken();
            AssertionError cause = assertThrows(AssertionError.class, () -> assertBoundedGrowth("structural mutant",
                    fixture -> mutant.extract(fixture.pcm(), fixture.format(), fixture.loudness(), token)));
            assertTrue(cause.getMessage().contains("allocation follows duration"), cause.getMessage());
            System.out.println("HANN_MUTANT_CAUSE reset structural: " + cause.getMessage());
        }
    }

    @Test
    void comparisonHasOneHannCallsitePerBranch() throws Exception
    {
        M004Classfile file;
        try (var input = ComparisonFeatureExtractor.class.getResourceAsStream("ComparisonFeatureExtractor.class"))
        {
            assertNotNull(input);
            file = M004Classfile.parse(input.readAllBytes());
        }
        String descriptor = "([FLcom/quickmaster/processing/dynamics/leveler/model/AudioFormat;"
                + "Lcom/quickmaster/processing/dynamics/leveler/CancellationToken;III[S[BI)Z";
        var branch = file.methods.stream().filter(method -> method.name().equals("extractBranch")
                && method.descriptor().equals(descriptor)).findFirst().orElseThrow();
        var hannCalls = branch.code().instructions().stream().filter(instruction -> instruction.opcode() == 184)
                .filter(instruction -> file.member(instruction.operand()).equals(
                        new M004Classfile.Member(file.owner, "hann", "(II)D"))).toList();
        assertEquals(1, hannCalls.size(), "Hann must only be evaluated by the branch energy setup");
        int firstSampleRateRead = branch.code().instructions().stream()
                .filter(instruction -> instruction.opcode() == 182)
                .filter(instruction -> file.member(instruction.operand()).equals(new M004Classfile.Member(
                        "com/quickmaster/processing/dynamics/leveler/model/AudioFormat", "sampleRateHz", "()I")))
                .mapToInt(M004Classfile.Instruction::offset).min().orElseThrow();
        assertTrue(hannCalls.get(0).offset() < firstSampleRateRead,
                "The sole Hann call must precede the row/sample loops");
    }

    private static void assertBoundedGrowth(String name, Function<Silence, Object> extract)
    {
        var management = ManagementFactory.getThreadMXBean();
        assertInstanceOf(com.sun.management.ThreadMXBean.class, management);
        var allocation = (com.sun.management.ThreadMXBean) management;
        assertTrue(allocation.isThreadAllocatedMemorySupported(), "Supported-Java allocation evidence is required");
        if (!allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
        Silence small = silence(48_000), large = silence(144_000);
        for (int warmup = 0; warmup < 2; warmup++)
        {
            assertNotNull(extract.apply(small));
            assertNotNull(extract.apply(large));
        }
        long thread = Thread.currentThread().getId();
        long[] smallBytes = new long[3], largeBytes = new long[3];
        for (int pair = 0; pair < 3; pair++)
        {
            if ((pair & 1) == 0)
            {
                smallBytes[pair] = measure(allocation, thread, extract, small);
                largeBytes[pair] = measure(allocation, thread, extract, large);
            }
            else
            {
                largeBytes[pair] = measure(allocation, thread, extract, large);
                smallBytes[pair] = measure(allocation, thread, extract, small);
            }
        }
        for (int pair = 0; pair < 3; pair++)
            System.out.println(name + " pair=" + pair + " smallBytes=" + smallBytes[pair]
                    + " largeBytes=" + largeBytes[pair] + " growth=" + (largeBytes[pair] - smallBytes[pair]));
        assertAll(IntStream.range(0, 3).mapToObj(pair -> () -> assertTrue(
                largeBytes[pair] - smallBytes[pair] <= 65_536L,
                name + " allocation follows duration: pair=" + pair + " small=" + smallBytes[pair]
                        + " large=" + largeBytes[pair] + " growth=" + (largeBytes[pair] - smallBytes[pair]))));
    }

    private static long measure(com.sun.management.ThreadMXBean allocation, long thread,
                                Function<Silence, Object> extract, Silence fixture)
    {
        long before = allocation.getThreadAllocatedBytes(thread);
        Object result = extract.apply(fixture);
        long after = allocation.getThreadAllocatedBytes(thread);
        assertNotNull(result);
        assertTrue(before >= 0L && after >= before, "Allocation counter must be available and monotonic");
        return after - before;
    }

    private static Silence silence(int frames)
    {
        int count = (frames + 4_799) / 4_800;
        double[] powers = new double[count], lufs = new double[count];
        Arrays.fill(powers, .01d);
        Arrays.fill(lufs, -20.691d);
        BitSet valid = new BitSet(count);
        valid.set(0, count);
        LoudnessTimeline loudness = new LoudnessTimeline(19_200, 144_000, 4_800,
                powers, valid, lufs, valid, MeasuredLoudness.absent());
        return new Silence(new float[frames * 2], new AudioFormat(48_000, 2, frames), loudness);
    }

    private record Silence(float[] pcm, AudioFormat format, LoudnessTimeline loudness) { }
}
