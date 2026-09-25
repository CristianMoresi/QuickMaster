package com.quickmaster.processing.dynamics.leveler.memory;

/** Deliberate test-only escape sentinel. It is never referenced by a product class. */
final class HarnessEscapeSentinel
{
    static volatile Object value;
    private HarnessEscapeSentinel() { }
}
