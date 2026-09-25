package com.quickmaster.processing.dynamics.leveler;

/** Keeps allocations observable only in isolated, generated detector mutants. */
final class BoundaryAllocationSink
{
    private static volatile Object retained;

    static void keep(Object value) { retained = value; }
    static void clear() { retained = null; }
}
