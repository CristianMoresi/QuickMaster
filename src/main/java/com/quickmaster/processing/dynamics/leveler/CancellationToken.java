package com.quickmaster.processing.dynamics.leveler;

/** Poll-only cancellation input; it is never retained by the analysis graph. */
public final class CancellationToken
{
    private volatile boolean cancelled;

    public CancellationToken()
    {
        this.cancelled = false;
    }

    public void cancel() { cancelled = true; }
    public boolean isCancelled() { return cancelled; }
}
