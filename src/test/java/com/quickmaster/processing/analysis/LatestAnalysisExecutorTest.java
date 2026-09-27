package com.quickmaster.processing.analysis;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LatestAnalysisExecutorTest {
    @Test void aCancelledButStillRunningJobNeverOverlapsItsReplacement() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        List<Integer> executed = Collections.synchronizedList(new ArrayList<>());
        try (LatestAnalysisExecutor executor = new LatestAnalysisExecutor()) {
            FutureTask<Void> first = new FutureTask<>(() -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                started.countDown();
                // Models a native/DSP chunk which cannot stop immediately on interrupt.
                while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { }
                active.decrementAndGet(); return null;
            });
            executor.replace(first); assertTrue(started.await(5, TimeUnit.SECONDS));
            List<FutureTask<Void>> requests = new ArrayList<>();
            try {
                for (int i = 0; i < 100; i++) {
                    int id = i;
                    FutureTask<Void> request = new FutureTask<>(() -> {
                        maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                        executed.add(id); active.decrementAndGet(); return null;
                    });
                    requests.add(request); executor.replace(request);
                }
                assertTrue(first.isCancelled());
                assertTrue(executed.isEmpty(), "Future cancellation is not actual worker completion");
                for (int i = 0; i < 99; i++) assertTrue(requests.get(i).isCancelled());
            } finally { release.countDown(); }
            requests.get(99).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(99), executed); assertEquals(1, maximum.get());
        } finally { release.countDown(); }
    }

    @Test void cancelRemovesPendingWorkAndCloseRejectsNewRequests() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        LatestAnalysisExecutor executor = new LatestAnalysisExecutor();
        try {
            executor.replace(new FutureTask<Void>(() -> {
                started.countDown();
                while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { }
                return null;
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            FutureTask<Void> pending = new FutureTask<>(() -> { fail("Cancelled pending work ran"); return null; });
            executor.replace(pending); executor.cancel(); assertTrue(pending.isCancelled());
            release.countDown(); executor.close();
            assertThrows(RejectedExecutionException.class, () -> executor.replace(new FutureTask<>(() -> null)));
        } finally { release.countDown(); executor.close(); }
    }
}
