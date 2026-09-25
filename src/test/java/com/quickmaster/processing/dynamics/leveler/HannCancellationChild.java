package com.quickmaster.processing.dynamics.leveler;

/** Separate VM entry for the real-product cancellation observer; no production instrumentation. */
public final class HannCancellationChild
{
    public static void main(String[] args)
    {
        var fixture = HannBitIdentityTest.fixture(192_000, 2, 120_007, 0);
        CancellationToken token = new CancellationToken();
        Object result = Boolean.parseBoolean(args[0])
                ? new ComparisonFeatureExtractor().extract(fixture.pcm(), fixture.format(), token)
                : new StructuralFeatureExtractor().extract(fixture.pcm(), fixture.format(), fixture.loudness(), token);
        finish(result);
    }

    public static void finish(Object result)
    {
        if (result != null) throw new AssertionError("Cancelled Hann extraction published a timeline");
    }
}
