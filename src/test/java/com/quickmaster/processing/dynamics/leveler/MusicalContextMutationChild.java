package com.quickmaster.processing.dynamics.leveler;

/** Calls identical assertions in a separate JVM with one isolated real-source mutant. */
public final class MusicalContextMutationChild
{
    public static void main(String[] args) throws Exception
    {
        switch (args[0])
        {
            case "last" -> MusicalContextAdversarialTest.lastSlot();
            case "mask" -> MusicalContextAdversarialTest.mask();
            case "rotation" -> MusicalContextAdversarialTest.rotation();
            case "previous" -> MusicalContextAdversarialTest.previous();
            case "predicate" -> MusicalContextAdversarialTest.predicate();
            case "q" -> new BoundaryOriginalNoveltyTest().chromaAndSpectralOnlyStepsReachContextsFromOriginalMultiscaleQ();
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.out.println("ASSERTIONS_PASSED");
    }
}
