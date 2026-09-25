package com.quickmaster.processing.dynamics.leveler.memory;

import java.lang.instrument.Instrumentation;

/** Minimal test-only premain agent. It installs no transformer and touches no product state. */
public final class ShadowReachabilityAgent
{
    private static volatile Instrumentation instrumentation;
    private ShadowReachabilityAgent() { }
    public static void premain(String ignored, Instrumentation value)
    {
        if (instrumentation != null || value == null) throw new IllegalStateException("AGENT_INVALID");
        instrumentation = value;
    }
    public static long shallowSize(Object value)
    {
        Instrumentation current = instrumentation;
        if (current == null) throw new IllegalStateException("AGENT_MISSING");
        if (value == null) throw new IllegalArgumentException("Cannot measure null as an identity");
        long bytes = current.getObjectSize(value);
        if (bytes <= 0L) throw new IllegalStateException("AGENT_NONPOSITIVE_SIZE");
        return bytes;
    }
    public static boolean present() { return instrumentation != null; }
}
