package com.quickmaster.processing.analysis;

import java.util.concurrent.*;

/**
 * One physical worker and at most one pending request. Cancelling a Future does
 * not mean its code has exited: the executor serializes actual execution too.
 * Superseded requests are removed rather than retained in an unbounded queue.
 */
public final class LatestAnalysisExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "QuickMaster-Analysis");
                thread.setDaemon(true);
                return thread;
            }) {
        @Override protected void afterExecute(Runnable task, Throwable failure) {
            synchronized (LatestAnalysisExecutor.this) {
                if (latest == task) latest = null;
            }
        }
    };
    private FutureTask<?> latest;

    public synchronized void replace(FutureTask<?> task) {
        if (executor.isShutdown()) throw new RejectedExecutionException("Analysis executor is closed.");
        cancel();
        latest = java.util.Objects.requireNonNull(task);
        executor.execute(task);
    }

    public synchronized void cancel() {
        if (latest != null) {
            latest.cancel(true);
            executor.remove(latest);
            latest = null;
        }
    }

    @Override public synchronized void close() {
        cancel();
        executor.shutdownNow();
    }
}
