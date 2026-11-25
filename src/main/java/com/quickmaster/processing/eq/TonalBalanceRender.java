package com.quickmaster.processing.eq;

import com.dspark.effects.TonalBalance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Renders a whole programme through the tonal balance, offline.
 * <p>
 * The tonal balance is one stream: what it does at a frame depends on all it
 * has heard before. Rendered so, a song takes about a tenth of its length.
 * Here the programme is cut into stretches of about twenty seconds rendered
 * at once on several threads, each warmed up on the fifteen seconds of
 * programme before it (the first one on the opening played backwards), and
 * every join is then checked: the curves the stretch before draws as it runs
 * past the join are compared with those the next one draws there.
 * <ul>
 *   <li>Where they agree (nearly always: the analysis forgets within
 *       seconds), the two renders are the same to far under the programme
 *       and a short crossfade joins them.</li>
 *   <li>Where they do not (a join in a quiet passage, where the stretch
 *       before still remembers the loud one it came from), the next stretch
 *       is discarded and the stream before carries on through it, exactly as
 *       one stream would. The join after it is then checked in turn.</li>
 * </ul>
 * The joins are settled in order from the start of the programme, so a stream
 * that carries on is always one whose own render stands; passages apart are
 * settled in the same pass, each on a thread of its own.
 * <p>
 * The cut and the checks depend on the programme alone, never on the threads
 * at hand: a render is the same on one thread as on many. Every stretch
 * starts on a grid of sixteen curve hops counted from the start of the
 * programme, so all of them read and draw at the very frames one stream
 * would.
 * <p>
 * The latency of the filter is taken off, so the result is as long as the
 * source and aligned with it. A mono programme is read as it is heard, the
 * same in both channels, so it takes the correction its stereo copy takes.
 * <p>
 * Along with the audio the render keeps the curve in play about twelve times
 * a second, for display.
 */
final class TonalBalanceRender
{
    /**
     * What the balance is asked to do.
     *
     * @param balance   share of the way the broad tone moves toward the even one
     * @param trimPeaks how much peaks are lowered
     * @param fillDips  how much dips are raised
     * @param raising   share of what it would raise that is raised
     * @param lowering  share of what it would lower that is lowered
     * @param detail    how fine peaks and dips are read, in octaves
     * @param rangeDb   the largest correction of a band
     */
    record Settings(double balance, double trimPeaks, double fillDips, double raising, double lowering,
                    double detail, double rangeDb)
    {
        /** Raising and lowering in full: the loudness is kept. */
        static Settings even(double balance, double trimPeaks, double fillDips, double detail, double rangeDb)
        {
            return new Settings(balance, trimPeaks, fillDips, 1.0, 1.0, detail, rangeDb);
        }

        /** Whether no correction can come of these settings. */
        boolean asksNothing()
        {
            return rangeDb == 0.0 || (balance == 0.0 && trimPeaks == 0.0 && fillDips == 0.0)
                    || (raising == 0.0 && lowering == 0.0);
        }
    }

    /**
     * How a programme is cut and joined.
     *
     * @param stretchSeconds programme rendered by one worker
     * @param warmUpSeconds  programme a stretch hears before its first frame
     * @param toleranceDb    largest difference between the curves of two stretches at their join
     *                       for the join to be a crossfade; over it the stream before carries on
     */
    record Plan(double stretchSeconds, double warmUpSeconds, double toleranceDb)
    {
        /**
         * Stretches of twenty seconds, fifteen of warm-up, curves within five
         * thousandths of a decibel. On real masters two stretches that agree
         * draw curves 0.0002 to 0.003 dB apart at their join and render 80 to
         * 110 dB under the programme from each other; where a stretch lacks a
         * past that still counts, they stand 0.013 to 0.24 dB apart.
         */
        static final Plan DEFAULT = new Plan(20.0, 15.0, 0.005);
        /** The whole programme as one stream. */
        static final Plan ONE_PIECE = new Plan(1e9, 15.0, 0.0);
    }

    /**
     * A render.
     *
     * @param samples        the programme balanced, interleaved as the source
     * @param curves         the curves kept, {@link #CURVE_POINTS} values each, in hundredths of a decibel
     * @param curveCount     how many curves
     * @param curveSpacing   frames of programme from one curve to the next
     * @param joinMismatchDb for each join, how far the curves of its two stretches stood apart, in dB
     * @param carriedOn      stretches rendered by a stream before them instead of their own
     * @param rounds         passes it took to carry them on; stretches apart are carried on in the same pass
     */
    record Result(float[] samples, short[] curves, int curveCount, int curveSpacing,
                  double[] joinMismatchDb, int carriedOn, int rounds) { }

    /** Points of a curve kept: every other one of those the filter plays, from 20 Hz to 20 kHz in log frequency. */
    static final int CURVE_POINTS = TonalBalance.POINTS / 2;

    private static final double JOIN_SECONDS = 0.1;       // crossfade from a stretch to the next
    private static final double CURVES_PER_SECOND = 12.0;
    private static final int BLOCK = 4096;

    private TonalBalanceRender() { }

    /** The frequency of a point of a kept curve, in hertz. */
    static double curveFrequency(int point)
    {
        return TonalBalance.getCurveFrequency(2 * point);
    }

    /** The position of a frequency on a kept curve, in points (0 to {@link #CURVE_POINTS} - 1). */
    static double curvePosition(double hz)
    {
        if (!(hz > 20.0)) return 0.0;
        double full = (TonalBalance.POINTS - 1) * Math.log(hz / 20.0) / Math.log(1000.0);
        return Math.min(0.5 * full, CURVE_POINTS - 1.0);
    }

    /**
     * Renders a programme.
     *
     * @param samples    the programme, interleaved; left as it is
     * @param channels   1 or 2
     * @param sampleRate its rate
     * @param settings   what is asked
     * @param maxWorkers threads to use at most (the result does not depend on it)
     * @param cancelled  polled while rendering; once true the render ends with a CancellationException
     */
    static Result render(float[] samples, int channels, int sampleRate, Settings settings,
                         int maxWorkers, BooleanSupplier cancelled)
    {
        return render(samples, channels, sampleRate, settings, maxWorkers, cancelled, Plan.DEFAULT);
    }

    /** As {@link #render(float[], int, int, Settings, int, BooleanSupplier)}, cut and joined as the plan says. */
    static Result render(float[] samples, int channels, int sampleRate, Settings settings,
                         int maxWorkers, BooleanSupplier cancelled, Plan plan)
    {
        final int frames = samples.length / channels;
        final int stretch = (int) Math.max(1L, Math.min(Math.round(plan.stretchSeconds() * sampleRate), Integer.MAX_VALUE));
        final int count = (int) Math.max(1L, Math.round((double) frames / stretch));
        final int warm = (int) Math.round(plan.warmUpSeconds() * sampleRate);
        final int join = count > 1 ? Math.max(1, (int) Math.round(JOIN_SECONDS * sampleRate)) : 0;
        final int workers = Math.min(count, Math.max(1, maxWorkers));

        final TonalBalance first = configured(settings, sampleRate, TonalBalance.Quality.RENDER);
        final int hop = first.getCurveHop();
        final int every = Math.max(1, (int) Math.round(sampleRate / (CURVES_PER_SECOND * hop)));
        final int slotFrames = every * hop;
        final int slots = (int) ((frames + slotFrames - 1L) / slotFrames);
        final AtomicBoolean stop = new AtomicBoolean();
        final Work work = new Work(samples, new float[samples.length], channels, frames, join,
                new short[Math.multiplyExact(slots, CURVE_POINTS)], new boolean[slots], slotFrames,
                () -> stop.get() || cancelled.getAsBoolean());

        // Every stretch at once, each on a stream of its own.
        final float[][] tails = new float[count][];
        final Stream[] streams = new Stream[count];
        final long grid = 16L * hop;
        final List<Runnable> jobs = new ArrayList<>(count);
        for (int k = 0; k < count; k++)
        {
            final int index = k, from = bound(k, frames, count), to = bound(k + 1, frames, count);
            if (k + 1 < count) tails[k] = new float[join * channels];
            jobs.add(() ->
            {
                TonalBalance tb = index == 0 ? first : configured(settings, sampleRate, TonalBalance.Quality.RENDER);
                Stream s = new Stream(work, tb, Math.floorDiv((long) from - warm, grid) * grid);
                if (index > 0) s.watchHead(from);
                s.deliver(from, to, from, tails[index]);
                s.advance();
                streams[index] = s;
            });
        }
        run(jobs, workers, stop);

        // The joins settled from the start of the programme on. A stretch
        // stands when the stream of the stretch before it, standing itself,
        // draws the same curves at their join, or has carried on through it;
        // otherwise that stream carries on through it now. In the same round
        // a stretch further on that leaves the one before it is taken ahead
        // of its turn by that one's stream, which may not stand in the end:
        // the stretch is then taken again when its turn comes.
        final int[] owner = new int[count];
        final double[] mismatch = new double[Math.max(0, count - 1)];
        for (int k = 0; k < count; k++) owner[k] = k;
        for (int k = 1; k < count; k++) mismatch[k - 1] = Stream.mismatch(streams[k - 1], streams[k]);
        final double tolerance = plan.toleranceDb();
        final boolean[] taken = new boolean[count];
        int standing = 1, rounds = 0;
        while (true)
        {
            while (standing < count)
            {
                final int before = owner[standing - 1];
                if (owner[standing] != before
                        && (owner[standing] != standing || Stream.mismatch(streams[before], streams[standing]) > tolerance)) break;
                standing++;
            }
            if (standing == count) break;

            jobs.clear();
            Arrays.fill(taken, false);
            for (int k = standing; k < count; k++)
            {
                final int stretchIndex = k, before = owner[k - 1];
                if (k > standing)
                {
                    // Ahead of its turn: its own render still in place, the stretch before it
                    // not changing hands in this round, and the stream of that stretch still
                    // holding the stretch it began with.
                    if (owner[k] != k || taken[k - 1] || owner[before] != before || taken[before]) continue;
                    if (!(Stream.mismatch(streams[before], streams[k]) > tolerance)) continue;
                }
                final int from = bound(k, frames, count), to = bound(k + 1, frames, count);
                // The curves kept for the stretch give way to those of the stream that takes it.
                for (int g = (int) ((from + slotFrames - 1L) / slotFrames); g < slots && (long) g * slotFrames < to; g++) work.drawn[g] = false;
                jobs.add(() ->
                {
                    Stream s = streams[before];
                    s.deliver(from + join, to, from, tails[stretchIndex]);
                    s.advance();
                });
                taken[k] = true;
            }
            run(jobs, workers, stop);
            for (int k = standing; k < count; k++) if (taken[k]) owner[k] = owner[k - 1];
            rounds++;
        }
        int carriedOn = 0;
        for (int k = 0; k < count; k++) if (owner[k] != k) carriedOn++;

        // Each stretch into the next: a crossfade between two streams, the
        // stream's own frames where it carried on.
        final float[] out = work.out;
        for (int k = 1; k < count; k++)
        {
            final int at = bound(k, frames, count);
            final float[] tail = tails[k - 1];
            final int length = Math.min(join, frames - at);
            final boolean same = owner[k] == owner[k - 1];
            for (int i = 0; i < length; i++)
            {
                double w = same ? 0.0 : (i + 0.5) / join;
                for (int c = 0; c < channels; c++)
                {
                    int o = (at + i) * channels + c;
                    out[o] = same ? tail[i * channels + c] : (float) ((1.0 - w) * tail[i * channels + c] + w * out[o]);
                }
            }
        }
        // A curve not drawn holds the one before it.
        final short[] curves = work.curves;
        for (int g = 1; g < slots; g++)
            if (!work.drawn[g]) System.arraycopy(curves, (g - 1) * CURVE_POINTS, curves, g * CURVE_POINTS, CURVE_POINTS);
        return new Result(out, curves, slots, slotFrames, mismatch, carriedOn, rounds);
    }

    /**
     * Renders a window of a programme on its own, for a provisional audition
     * while the whole render is on its way: one stream at realtime quality
     * that starts cold at the window's first frame, so the correction of its
     * first second or more is still settling. As long as the window and
     * aligned with it.
     *
     * @param samples    the window, interleaved; left as it is
     * @param channels   1 or 2
     * @param sampleRate its rate
     * @param settings   what is asked
     * @param cancelled  polled while rendering; once true the render ends with a CancellationException
     */
    static float[] renderWindow(float[] samples, int channels, int sampleRate, Settings settings, BooleanSupplier cancelled)
    {
        final int frames = samples.length / channels;
        final Work work = new Work(samples, new float[samples.length], channels, frames, 0, new short[0], new boolean[0], 1, cancelled);
        Stream s = new Stream(work, configured(settings, sampleRate, TonalBalance.Quality.REALTIME), 0L);
        s.deliver(0, frames, 0, null);
        s.advance();
        return work.out;
    }

    private static int bound(int k, int frames, int count)
    {
        return (int) ((long) k * frames / count);
    }

    private static TonalBalance configured(Settings s, int sampleRate, TonalBalance.Quality quality)
    {
        TonalBalance tb = new TonalBalance();
        tb.setQuality(quality);
        tb.setBalance(s.balance());
        tb.setTrimPeaks(s.trimPeaks());
        tb.setFillDips(s.fillDips());
        tb.setRaising(s.raising());
        tb.setLowering(s.lowering());
        tb.setDetail(s.detail());
        tb.setRange(s.rangeDb());
        tb.prepare(sampleRate, 2);
        return tb;
    }

    private static void run(List<Runnable> jobs, int workers, AtomicBoolean stop)
    {
        if (workers <= 1 || jobs.size() <= 1)
        {
            for (Runnable job : jobs) job.run();
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(workers, jobs.size()), task ->
        {
            Thread t = new Thread(task, "auto-eq-render");
            t.setDaemon(true);
            // A step under the rest, so playback and the interface keep their turn.
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        try
        {
            List<Future<?>> pending = new ArrayList<>(jobs.size());
            for (Runnable job : jobs) pending.add(pool.submit(job));
            for (Future<?> f : pending)
            {
                try
                {
                    f.get();
                }
                catch (InterruptedException e)
                {
                    stop.set(true);
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Auto EQ cancelled.");
                }
                catch (ExecutionException e)
                {
                    stop.set(true);
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException r) throw r;
                    if (cause instanceof Error err) throw err;
                    throw new IllegalStateException(cause);
                }
            }
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    /** What every stream of a render shares: the programme, where its render goes and the curves kept. */
    private record Work(float[] in, float[] out, int channels, int frames, int join,
                        short[] curves, boolean[] drawn, int slotFrames, BooleanSupplier halted) { }

    /**
     * One stream through the tonal balance: it renders a stretch, and the
     * stretches after it when it has to carry on. It keeps the curves it
     * draws as it runs past the two ends of its stretch, to be compared with
     * those of its neighbours.
     */
    private static final class Stream
    {
        private static final int HEAD = 0, TAIL = 1;

        private final Work work;
        private final TonalBalance tb;
        private final int latency, hop;
        private final long start;             // the frame the stream began at
        private long position, designs;       // the frame fed next; curves drawn so far
        private int from, to, slotFrom;       // delivering the frames [from, to); keeping the curves of [slotFrom, to)
        private float[] tail;                 // and the frames of the join after them
        private final double[][] watched = new double[2][];
        private final int[] watchedCount = new int[2];
        private final long[] watchFrom = { Long.MAX_VALUE, Long.MAX_VALUE }, watchTo = { Long.MIN_VALUE, Long.MIN_VALUE };
        private final double[][] block = new double[2][BLOCK];

        Stream(Work work, TonalBalance tb, long start)
        {
            this.work = work;
            this.tb = tb;
            this.latency = tb.getLatency();
            this.hop = tb.getCurveHop();
            this.start = start;
            this.position = start;
            int curvesWatched = (work.join() + latency) / hop + 2;
            watched[HEAD] = new double[curvesWatched * TonalBalance.POINTS];
            watched[TAIL] = new double[curvesWatched * TonalBalance.POINTS];
            tb.setCurveListener(this::curveDrawn);
        }

        /** Keeps the curves drawn while the join that begins at this frame is fed: the head of this stream's stretch. */
        void watchHead(long frame)
        {
            watch(HEAD, frame);
        }

        private void watch(int which, long frame)
        {
            watchFrom[which] = frame;
            watchTo[which] = frame + work.join() + latency;
            watchedCount[which] = 0;
        }

        /**
         * Sets what the stream renders next: the frames from {@code from} to
         * before {@code to} into the render, the join after them into
         * {@code tail} (null for the last stretch), and the curves kept from
         * {@code slotFrom} on.
         */
        void deliver(int from, int to, int slotFrom, float[] tail)
        {
            this.from = from;
            this.to = to;
            this.slotFrom = slotFrom;
            this.tail = tail;
            if (tail != null) watch(TAIL, to);
        }

        /** Runs until everything set by deliver() has come out of the filter. */
        void advance()
        {
            final float[] in = work.in(), out = work.out();
            final int channels = work.channels(), frames = work.frames(), join = work.join();
            final long end = (tail != null ? (long) to + join : to) + latency;
            final double[] left = block[0], right = block[1];
            while (position < end)
            {
                if (work.halted().getAsBoolean()) throw new CancellationException("Auto EQ cancelled.");
                final long at = position;
                final int n = (int) Math.min(BLOCK, end - at);
                if (at >= 0 && at + n <= frames)
                {
                    int base = (int) at;
                    if (channels == 2)
                        for (int i = 0; i < n; i++)
                        {
                            left[i] = in[2 * (base + i)];
                            right[i] = in[2 * (base + i) + 1];
                        }
                    else
                        for (int i = 0; i < n; i++) left[i] = right[i] = in[base + i];
                }
                else
                {
                    // Before the programme and after it: the programme mirrored.
                    for (int i = 0; i < n; i++)
                    {
                        int f = mirrored(at + i, frames);
                        left[i] = in[f * channels];
                        right[i] = in[f * channels + channels - 1];
                    }
                }
                tb.process(block, n);
                position = at + n;

                // Sample i of the block is frame `first + i` of the programme.
                final long first = at - latency;
                int begin = (int) Math.max(0L, from - first), stop = (int) Math.min(n, to - first);
                for (int i = begin; i < stop; i++)
                {
                    int o = (int) (first + i) * channels;
                    out[o] = (float) left[i];
                    if (channels == 2) out[o + 1] = (float) right[i];
                }
                if (tail != null)
                {
                    begin = (int) Math.max(0L, to - first);
                    stop = (int) Math.min(n, to + (long) join - first);
                    for (int i = begin; i < stop; i++)
                    {
                        int o = (int) (first + i - to) * channels;
                        tail[o] = (float) left[i];
                        if (channels == 2) tail[o + 1] = (float) right[i];
                    }
                }
            }
        }

        private void curveDrawn(double[] curve)
        {
            final long fed = start + (++designs) * hop;      // frames fed when this curve is drawn
            for (int which = 0; which < 2; which++)
                if (fed > watchFrom[which] && fed <= watchTo[which]
                        && (watchedCount[which] + 1) * TonalBalance.POINTS <= watched[which].length)
                    System.arraycopy(curve, 0, watched[which], watchedCount[which]++ * TonalBalance.POINTS, TonalBalance.POINTS);

            // The frame at which the curve drawn now is fully in play.
            final long shown = fed - latency + hop;
            if (shown < slotFrom) return;
            final int slotFrames = work.slotFrames();
            final long slot = shown / slotFrames, begins = slot * slotFrames;
            final boolean[] drawn = work.drawn();
            if (begins < slotFrom || begins >= to || slot >= drawn.length || drawn[(int) slot]) return;
            drawn[(int) slot] = true;
            final short[] curves = work.curves();
            final int at = (int) slot * CURVE_POINTS;
            for (int i = 0; i < CURVE_POINTS; i++) curves[at + i] = (short) Math.round(100.0 * curve[2 * i]);
        }

        /**
         * How far the curves a stream drew past the end of its stretch stand
         * from those the next stream drew at the head of its own, in dB: the
         * largest difference at any point of any of them. Infinite when they
         * are not the same curves in number.
         */
        static double mismatch(Stream before, Stream after)
        {
            if (before.watchedCount[TAIL] != after.watchedCount[HEAD] || before.watchedCount[TAIL] == 0)
                return Double.POSITIVE_INFINITY;
            final double[] a = before.watched[TAIL], b = after.watched[HEAD];
            final int values = before.watchedCount[TAIL] * TonalBalance.POINTS;
            double worst = 0.0;
            for (int i = 0; i < values; i++) worst = Math.max(worst, Math.abs(a[i] - b[i]));
            return worst;
        }
    }

    /** The frame a position outside the programme reads: the programme mirrored at each of its ends. */
    static int mirrored(long position, int frames)
    {
        if (frames <= 1) return 0;
        long period = 2L * frames;
        long m = Math.floorMod(position, period);
        return (int) (m < frames ? m : period - 1 - m);
    }
}
