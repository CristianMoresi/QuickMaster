package com.dspark.effects;

import com.dspark.core.AuditoryBands;
import com.dspark.core.AuditoryScale;
import com.dspark.core.DetailAnalysis;
import com.dspark.core.DetailFilter;
import com.dspark.core.DspMath;
import com.dspark.core.FFTRealDouble;
import com.dspark.core.SpectralEnvelope;
import com.dspark.core.SpectralFocus;
import com.dspark.core.WindowFunctions;

/**
 * An adaptive equalizer driven by an auditory model: it measures the loudness
 * of the programme in bands of the ear's scale and moves the programme toward
 * a balanced shape, raising what is missing and taming what stands out, as
 * fine as {@link #setDetail} and with the total loudness kept.
 * <p>
 * Two readings. The broad one is a model of hearing: every 128 samples at 48
 * kHz (64 at {@link Quality#RENDER}), a transform of 2048 under an asymmetric
 * window whose weight sits on the most recent samples. The power spectrum is
 * smoothed across frequency with a width that follows frequency, read in 72
 * bands uniform on the Bark scale from 20 Hz to 20 kHz and smoothed across
 * the bands (the ear's spread). In each band the covariance of the two
 * channels is smoothed in time, longer in the low end, and the band's power
 * is its larger eigenvalue: the level of the dominant component wherever it
 * sits in the stereo image. The specific loudness of each band (the power law
 * over the threshold in quiet) is smoothed in time and read in decibels of
 * power.
 * <p>
 * Each band counts by how audible it is: nothing while its power is under its
 * threshold in quiet, in full from 10 dB over it. A band far under the
 * programme ({@link #setFloor} dB under its loudest band) is the floor of a
 * recording or the end of the content: the balance does not give it back.
 * <p>
 * From there the balance: a broad trend across the bands against a reference
 * shape, flat loudness per Bark tilted by {@link #setTilt} around 1 kHz.
 * {@link #setBalance} is the share of the way the trend moves toward it,
 * through a soft knee.
 * <p>
 * The fine reading is the detail ({@link #setDetail}, 1 to 1/24 of an octave):
 * the power at 1024 points of log frequency, through a window that follows
 * the detail, smoothed in time. Each point stands against its two sides: a
 * dip is a point under both, a peak one over both, so a slope and the fall of
 * the content at an end are neither. {@link #setFillDips} raises the dips and
 * {@link #setTrimPeaks} lowers the peaks.
 * <p>
 * The corrections are limited ({@link #setRange}) and faded at the edges of
 * {@link #setFrequencyRange}, and then offset so the total loudness of the
 * programme does not change (the correction redistributes);
 * {@link #setRaising} and {@link #setLowering} give what goes up and what
 * goes down their own shares of all of it.
 * <p>
 * The curve, the broad balance between the bands' centres plus the detail at
 * each point, is played by a minimum-phase {@link DetailFilter}.
 * <p>
 * Latency ({@link #getLatency()}): 774 samples at 44.1 and 48 kHz, doubling
 * by octaves with the rate. Not thread-safe: set, prepare and process from
 * one thread. Non-finite values are ignored.
 */
public final class TonalBalance
{
    /** Bands of the loudness model. */
    public static final int BANDS = 72;
    /** Channels analysed (the others take the same curve). */
    public static final int MAX_CHANNELS = 2;
    /** Points of the curve the filter plays, evenly in log frequency from 20 Hz to 20 kHz. */
    public static final int POINTS = DetailFilter.POINTS;

    /**
     * What a balanced programme is. FLAT_LOUDNESS, the default: the same
     * loudness in every band of the ear's scale. EVEN_POWER: the loudness,
     * band by band, of a programme with the same power in every band at this
     * programme's level, read through the same model.
     */
    public enum Reference { FLAT_LOUDNESS, EVEN_POWER }

    /**
     * How the analysis follows the programme in time. BY_BAND, the default:
     * two smoothings with the same time per band, longer in the low end (200
     * ms at 1 kHz, 400 ms at 100 Hz). COMMON: a short integration per band
     * and then one response time for every band ({@link #setResponseTime}).
     */
    public enum Response { BY_BAND, COMMON }

    /**
     * How often the two readings run (applies at the next prepare()).
     * REALTIME, the default: the model every sixteenth of its transform, the
     * long windows of the detail in turns. RENDER: the model twice as often
     * and every window at every hop. The latency is the same in both.
     */
    public enum Quality { REALTIME, RENDER }

    /** Receives each curve as it is drawn (a view of internal storage: read, do not keep). */
    public interface CurveListener { void curveDrawn(double[] curveDb); }

    private static final double READ_ACROSS = 0.1;         // what a band shares with the next when the detail's moves are counted
    private static final double SIDE_OCTAVES = 0.5;        // reach of the two sides a point is read against
    private static final double FINEST_DIP = 1.0 / 6.0;    // a dip is looked for no finer, in octaves
    private static final double DIP_LIMIT = 18.0;          // a dip counts no deeper
    private static final double EMPTY = 45.0;              // this far under its sides a point is empty rather than low
    private static final double EMPTY_FADE = 15.0;         // and is let go over this many dB before that
    private static final double SHORTEST_SMOOTHING = 0.4;  // the detail's smoothing at the top, in seconds
    private static final double SHORTEST_COMMON = 0.1;     // and its shortest with Response.COMMON
    private static final double DETAIL_GLIDE_SECONDS = 0.1;
    private static final double AUDIBLE_SPAN = 10.0;       // dB over the threshold in quiet from which a band counts in full
    private static final double MASKED_SPAN = 10.0;        // dB over the floor from which a band counts in full
    private static final double OFFSET_LIMIT = 60.0;       // largest loudness offset looked for, in dB
    private static final int OFFSET_STEPS = 24;
    private static final int MOMENTS = 4;                  // of a bin of two channels: both powers and their cross term
    private static final double MID_EXPONENT = 0.23;
    private static final double PER_DB = 0.23025850929940457;        // ln(10) / 10
    private static final double DB_PER_LN = 4.3429448190325183;      // 10 / ln(10)
    private static final double QUARTER_DB_PER_LN = 17.371779276130073;   // 40 / ln(10)

    private double fs = 48000.0, powerScale = 1.0, modelNoiseBins = 1.5;
    private int channels = 0, fft = 2048, hop = 128, bins = 1025;
    private int ringPos = 0, hopCounter = 0;
    private boolean prepared = false, warm = false, render = false;

    private double balance = 0.5, fillDips = 0.3, trimPeaks = 0.5, tilt = 0.0;
    private double balanceAsymmetry = 0.0, balanceBias = 0.0, raising = 1.0, lowering = 1.0;
    private double range = 6.0, knee = 3.0, loHz = 40.0, hiHz = 16000.0, referenceSpl = 100.0;
    private boolean levelComp = false, refDirty = false, localSlope = false, timeFocus = false;
    private Reference reference = Reference.FLAT_LOUDNESS;
    private Response response = Response.BY_BAND;
    private Quality quality = Quality.REALTIME, qualityInUse = Quality.REALTIME;
    private boolean qualityFollowsRender = false, offlineRendering = false;
    private double responseMs = 60.0, listening = 0.0;
    private double rangeKnee = 0.0, floor = 30.0, contrastKnee = 3.0, detail = 1.0 / 4.0;

    private final double[] published = new double[BANDS];
    private double loudnessChange = 0.0, raised = 0.0, lowered = 0.0, total = -200.0, centroidShift = 0.0;

    private FFTRealDouble transform;
    private final AuditoryBands bands = new AuditoryBands();
    private final SpectralEnvelope envelope = new SpectralEnvelope();
    private final SpectralFocus focus = new SpectralFocus();
    private double[][] focusRe = new double[2][0], focusIm = new double[2][0];
    private double[] window = new double[0], frame = new double[0], moments = new double[0];
    private final double[] bandMoments = new double[MOMENTS * BANDS];
    private final double[] centreOctave = new double[BANDS];
    private double[][] ring = new double[0][], spectra = new double[0][];
    private final double[] ll = new double[BANDS], rr = new double[BANDS], lr = new double[BANDS], li = new double[BANDS];
    private final double[] loud = new double[BANDS], evenLoud = new double[BANDS], corr = new double[BANDS];
    private final double[] tmpA = new double[BANDS], tmpB = new double[BANDS], tmpC = new double[BANDS], tmpD = new double[BANDS];
    private final double[] timeCoeff = new double[BANDS], briefCoeff = new double[BANDS];
    // Work of a hop of the model.
    private final double[] level = new double[BANDS], trend = new double[BANDS], weight = new double[BANDS];
    private final double[] exponent = new double[BANDS], steep = new double[BANDS], audible = new double[BANDS];
    private final double[] weighed = new double[BANDS], present = new double[BANDS], inRange = new double[BANDS];
    private final double[] part = new double[BANDS], moved = new double[BANDS], held = new double[BANDS];

    // The detail, and the filter that plays both.
    private final DetailFilter filter = new DetailFilter();
    private final DetailAnalysis analysis = new DetailAnalysis();
    private final double[] base = new double[BANDS];        // the broad curve at the model's centres
    private final double[] detailMean = new double[BANDS];  // what the detail moves in each band, by power
    private final double[] heldSum = new double[POINTS + 1], movedSum = new double[POINTS + 1];
    private final double[] pointShare = new double[POINTS], seenShare = new double[POINTS];
    private final int[] seenFrom = new int[POINTS], seenTo = new int[POINTS];
    // Where a point lies between the centres of the model's bands.
    private final int[] placeBand = new int[POINTS];
    private final double[] placePart = new double[POINTS];
    private final double[] placeWeight = new double[4 * POINTS];
    private final double[] power = new double[POINTS], smoothPower = new double[POINTS], seen = new double[POINTS];
    private final double[] levelDb = new double[POINTS], gapLevel = new double[POINTS], heard = new double[POINTS];
    private final double[] asked = new double[POINTS], detailGain = new double[POINTS];
    private final double[] lowerSide = new double[POINTS], upperSide = new double[POINTS], quarter = new double[POINTS];
    private final double[] octave = new double[POINTS], threshold = new double[POINTS], slowCoeff = new double[POINTS];
    private final double[] sum = new double[POINTS + 1];
    private double hopSeconds = 256.0 / 48000.0, sidePole = 0.98;
    private double lastDetail = 1.0 / 4.0, detailGlide = 0.0;
    private double gapShare = 0.0;   // how much the space between partials is left alone
    private int top = 0, aside = 8, acrossHalf = 1, gapHalf = 0;

    private double[][] current;      // the block being processed
    private int currentChannels = 0;
    private CurveListener curveListener;
    private double[][] planar = new double[0][];

    private final DetailFilter.Feed feeder = this::feed;
    private final DetailFilter.Design designer = (channel, curveDb) ->
    {
        analysis.update(currentChannels);
        design(curveDb);
        if (curveListener != null) curveListener.curveDrawn(curveDb);
    };

    /** Allocates for a rate and a layout (an invalid one is ignored). */
    public void prepare(double sampleRate, int numChannels)
    {
        if (!Double.isFinite(sampleRate) || !(sampleRate > 0.0) || numChannels < 1) return;
        prepared = false;
        fs = sampleRate;
        channels = Math.min(numChannels, MAX_CHANNELS);
        // The model scales by octaves with the rate (2048 at 44.1 to 48 kHz).
        int octaves = (int) lround(Math.log(Math.max(fs, 8000.0) / 48000.0) / Math.log(2.0));
        fft = Math.max(1024, Math.min(2048 << Math.max(0, octaves), 16384));
        if (octaves < 0) fft = Math.max(512, 2048 >> -octaves);
        render = quality == Quality.RENDER || (qualityFollowsRender && offlineRendering);
        qualityInUse = render ? Quality.RENDER : Quality.REALTIME;
        int realtimeHop = Math.max(16, fft / 16);
        hop = render ? Math.max(8, realtimeHop / 2) : realtimeHop;
        int fall = fft / 8;
        transform = new FFTRealDouble(fft);
        window = new double[fft];
        WindowFunctions.asymmetricHann(window, fft, fall);
        double sw = 0.0, squares = 0.0;
        for (double w : window) { sw += w; squares += w * w; }
        // Power scale: a full-scale sine reads 1 in its bin.
        powerScale = 4.0 / (sw * sw);
        // What a band of noise reads over what it holds, in that scale.
        modelNoiseBins = fft * squares / (sw * sw);
        bins = fft / 2 + 1;
        bands.prepare(fs, fft, BANDS, 20.0, Math.min(20000.0, 0.45 * fs));
        bands.setReferenceSpl(referenceSpl);
        envelope.prepare(bins, 1.0 / 6.0);
        focus.prepare(window, fft, fft, 1.0 / 6.0, 1.0);
        focusRe = new double[2][bins];
        focusIm = new double[2][bins];
        ring = new double[channels][fft];
        spectra = new double[channels][fft + 2];
        frame = new double[fft];
        moments = new double[MOMENTS * bins];
        for (int b = 0; b < BANDS; b++) centreOctave[b] = Math.log(bands.getCentre(b) / 1000.0) / Math.log(2.0);
        for (int b = 0; b < BANDS; b++)
        {
            double tau = 0.001 * Math.max(20.0, Math.min(200.0 * Math.pow(1000.0 / bands.getCentre(b), 0.3), 2000.0));
            timeCoeff[b] = Math.exp(-(double) hop / (tau * fs));
            // The short integration of Response.COMMON: four periods of the centre, 8 to 100 ms.
            double brief = Math.max(0.008, Math.min(4.0 / bands.getCentre(b), 0.1));
            briefCoeff[b] = Math.exp(-(double) hop / (brief * fs));
        }
        filter.prepare(fs, numChannels);
        filter.setLinked(true);
        analysis.setLinked(true);
        analysis.setExhaustive(render);
        analysis.setTimeFocus(false);
        analysis.prepare(fs, channels);
        planar = new double[Math.min(numChannels, DetailFilter.MAX_CHANNELS)][0];
        prepareDetail();
        prepared = true;
        reset();
    }

    /** Clears the analysis, the corrections and the filter. */
    public void reset()
    {
        for (double[] r : ring) java.util.Arrays.fill(r, 0.0);
        for (double[] v : new double[][] { ll, rr, lr, li, loud, evenLoud, corr }) java.util.Arrays.fill(v, 0.0);
        ringPos = 0;
        hopCounter = 0;
        warm = false;
        filter.reset();
        analysis.reset();
        resetDetail();
        java.util.Arrays.fill(published, 0.0);
        loudnessChange = 0.0;
        raised = 0.0;
        lowered = 0.0;
        total = -200.0;
        centroidShift = 0.0;
    }

    // -- Controls ------------------------------------------------------------

    /** How far the trend moves toward the reference shape, 0 to 1 (default 0.5). */
    public void setBalance(double amount) { if (Double.isFinite(amount)) balance = clamp(amount, 0.0, 1.0); }
    /** How much dips (a detail under both of its sides, a sixth of an octave or wider) are raised, 0 to 1 (default 0.3). */
    public void setFillDips(double amount) { if (Double.isFinite(amount)) fillDips = clamp(amount, 0.0, 1.0); }
    /** How much peaks (a detail over both of its sides) are lowered, 0 to 1 (default 0.5). */
    public void setTrimPeaks(double amount) { if (Double.isFinite(amount)) trimPeaks = clamp(amount, 0.0, 1.0); }
    /** Tilt of the reference shape in dB per octave around 1 kHz, -6 to +6 (default 0: flat loudness per Bark); it acts through setBalance(). */
    public void setTilt(double dbPerOctave) { if (Double.isFinite(dbPerOctave)) tilt = clamp(dbPerOctave, -6.0, 6.0); }
    /** Largest correction of a band, 0 to 24 dB (default 6). */
    public void setRange(double db) { if (Double.isFinite(db)) range = clamp(db, 0.0, 24.0); }
    /** Knee of the balance, 0.1 to 12 dB (default 3); the dips and peaks have setContrastKnee(). */
    public void setKnee(double db) { if (Double.isFinite(db)) knee = clamp(db, 0.1, 12.0); }
    /**
     * How differently the balance lifts what is lacking and lowers what is in
     * excess, -1 to 1 (default 0: alike). Toward 1 the bands under the
     * reference are lifted by up to twice the share setBalance() gives and
     * those over it lowered by less, down to nothing; toward -1 the other way.
     */
    public void setBalanceAsymmetry(double amount) { if (Double.isFinite(amount)) balanceAsymmetry = clamp(amount, -1.0, 1.0); }
    /** Moves the point the balance tells lacking from excess at, -6 to +6 dB (default 0: the programme's own mean). */
    public void setBalanceBias(double db) { if (Double.isFinite(db)) balanceBias = clamp(db, -6.0, 6.0); }
    /**
     * How much of what the processor would raise is raised, 0 to 1 (default
     * 1: all of it). The share reaches everything that moves the curve
     * upward: the lift of the balance, the filling of dips, the offset that
     * keeps the total loudness when it raises (the corrections took loudness
     * away) and what the smooth line of the balance swings over its bands.
     * At 0 no point of the curve is over zero. An offset that is not whole
     * no longer keeps the loudness: getLoudnessChange() tells by how much.
     * setListeningCompensation() stands apart from it.
     */
    public void setRaising(double share) { if (Double.isFinite(share)) raising = clamp(share, 0.0, 1.0); }
    /**
     * How much of what the processor would lower is lowered, 0 to 1 (default
     * 1): the cut of the balance, the trimming of peaks, the offset that
     * keeps the total loudness when it lowers and what the smooth line of the
     * balance swings under its bands. At 0 no point of the curve is under
     * zero.
     */
    public void setLowering(double share) { if (Double.isFinite(share)) lowering = clamp(share, 0.0, 1.0); }
    /** Knee of the dips and peaks, 0.1 to 12 dB (default 3). */
    public void setContrastKnee(double db) { if (Double.isFinite(db)) contrastKnee = clamp(db, 0.1, 12.0); }
    /**
     * The detail: how finely the peaks and the dips (these no finer than a
     * sixth) are read, played and drawn over the whole band, an octave to
     * 1/24 (default 1/4). It moves while playing, at the same latency.
     */
    public void setDetail(double octaves)
    {
        if (Double.isFinite(octaves)) detail = clamp(octaves, DetailAnalysis.FINEST, DetailAnalysis.WIDEST);
    }
    /** Rounds the approach to the range, 0 to 6 dB (0, the default, is the hard limit). */
    public void setRangeKnee(double db) { if (Double.isFinite(db)) rangeKnee = clamp(db, 0.0, 6.0); }
    /**
     * How far under its loudest band (in the model's level) a band may lie and
     * still be given back by the balance, 10 to 120 dB (default 30).
     */
    public void setFloor(double db) { if (Double.isFinite(db)) floor = clamp(db, 10.0, 120.0); }
    /** Frequencies the corrections act on, with edges one Bark wide (default 40 Hz to 16 kHz). */
    public void setFrequencyRange(double lowHz, double highHz)
    {
        if (!Double.isFinite(lowHz) || !Double.isFinite(highHz)) return;
        double lo = clamp(Math.min(lowHz, highHz), 10.0, 24000.0);
        loHz = lo;
        hiHz = clamp(Math.max(lowHz, highHz), lo, 24000.0);
    }
    /** The shape the programme is compared against (null reads as FLAT_LOUDNESS). */
    public void setReference(Reference r) { reference = r == Reference.EVEN_POWER ? Reference.EVEN_POWER : Reference.FLAT_LOUDNESS; }
    /** REALTIME or RENDER (applies at the next prepare()). */
    public void setQuality(Quality q) { quality = q == Quality.RENDER ? Quality.RENDER : Quality.REALTIME; }
    /** With it on, a prepare() made while the host renders offline takes the RENDER quality whatever setQuality() says. */
    public void setQualityFollowsRender(boolean on) { qualityFollowsRender = on; }
    /** Tells the processor whether the host renders offline (read by the next prepare()). */
    public void setOfflineRendering(boolean on) { offlineRendering = on; }
    /** How the analysis follows the programme in time (null reads as BY_BAND). */
    public void setResponse(Response r) { response = r == Response.COMMON ? Response.COMMON : Response.BY_BAND; }
    /** The response time of Response.COMMON, 10 to 2000 ms (default 60). */
    public void setResponseTime(double ms) { if (Double.isFinite(ms)) responseMs = clamp(ms, 10.0, 2000.0); }
    /**
     * Compensates a listening level this many dB lower than the one the
     * programme is balanced at, -30 to +30 (default 0).
     */
    public void setListeningCompensation(double db) { if (Double.isFinite(db)) listening = clamp(db, -30.0, 30.0); }
    /** Loudness to decibels with each band's exponent (default off: one exponent). */
    public void setLevelCompensation(boolean on) { levelComp = on; }
    /** Loudness to decibels with the slope of the loudness curve at each band's own level (default off). */
    public void setLocalSlope(boolean on) { localSlope = on; }
    /**
     * Reads each band of the model through a stretch of the analysis window of
     * its own, shorter as frequency rises (default off: the whole window at
     * every frequency).
     */
    public void setTimeFocus(boolean on) { timeFocus = on; }
    /** The SPL a full-scale sine stands for, 60 to 130 dB (default 100). */
    public void setReferenceLevel(double dbSpl)
    {
        if (!Double.isFinite(dbSpl)) return;
        referenceSpl = clamp(dbSpl, 60.0, 130.0);
        refDirty = true;
    }
    /** Receives each curve as it is drawn (null for none). */
    public void setCurveListener(CurveListener listener) { curveListener = listener; }

    public double getBalance() { return balance; }
    public double getFillDips() { return fillDips; }
    public double getTrimPeaks() { return trimPeaks; }
    public double getTilt() { return tilt; }
    public double getRange() { return range; }
    public double getKnee() { return knee; }
    public double getBalanceAsymmetry() { return balanceAsymmetry; }
    public double getBalanceBias() { return balanceBias; }
    public double getRaising() { return raising; }
    public double getLowering() { return lowering; }
    public double getContrastKnee() { return contrastKnee; }
    public double getDetail() { return detail; }
    public double getRangeKnee() { return rangeKnee; }
    public double getFloor() { return floor; }
    public double getLowFrequency() { return loHz; }
    public double getHighFrequency() { return hiHz; }
    public boolean getLevelCompensation() { return levelComp; }
    public boolean getLocalSlope() { return localSlope; }
    public boolean getTimeFocus() { return timeFocus; }
    public Reference getReference() { return reference; }
    public Response getResponse() { return response; }
    public Quality getQuality() { return quality; }
    public boolean getQualityFollowsRender() { return qualityFollowsRender; }
    /** The quality the last prepare() took. */
    public Quality getQualityInUse() { return qualityInUse; }
    public double getResponseTime() { return responseMs; }
    public double getListeningCompensation() { return listening; }
    public double getReferenceLevel() { return referenceSpl; }

    /** The latency in samples. */
    public int getLatency() { return filter.getLatency(); }
    /** Samples from one curve to the next. */
    public int getCurveHop() { return filter.getHopSize(); }
    /** The centre of a band of the model, in hertz. */
    public double getBandCentre(int band) { return bands.getCentre(band); }
    /** The frequency of a point of the curve, in hertz. */
    public static double getCurveFrequency(int point) { return DetailFilter.frequency(point); }
    /**
     * The correction of a band of the model in dB, the detail in it counted
     * by the power it moves there.
     */
    public double getCorrection(int band) { return published[Math.max(0, Math.min(band, BANDS - 1))]; }
    /**
     * The change of the programme's total loudness the corrections make, in dB
     * of the loudness ratio: 0 while it is conserved, away from 0 only when
     * the limits leave the bands in range no room to make up for it.
     */
    public double getLoudnessChange() { return loudnessChange; }
    /** The share of the programme's loudness that the bands raised have gained (0 or more). */
    public double getLoudnessRaised() { return raised; }
    /** The share of the programme's loudness that the bands lowered have lost (0 or less). */
    public double getLoudnessLowered() { return lowered; }
    /** The programme's total loudness as the model reads it, in dB (-200 in silence). */
    public double getLoudness() { return total; }
    /** How far the corrections move the centre of the loudness along the ear's scale, in Bark (positive: brighter). */
    public double getCentroidShift() { return centroidShift; }

    // -- Processing ----------------------------------------------------------

    /**
     * Analyses and equalizes the programme in place.
     *
     * @param buffer  one array per channel
     * @param samples the samples of the block (from index 0 of each channel)
     */
    public void process(double[][] buffer, int samples)
    {
        if (!prepared || buffer == null || samples <= 0) return;
        int nCh = Math.min(buffer.length, channels);
        if (nCh <= 0) return;
        if (refDirty)
        {
            refDirty = false;
            bands.setReferenceSpl(referenceSpl);
            lastDetail = detail;
            refreshDetail();
        }
        else if (detail != lastDetail)
        {
            lastDetail = detail;
            refreshDetail();
        }
        current = buffer;
        currentChannels = nCh;
        filter.process(buffer, samples, feeder, designer);
        current = null;
    }

    /**
     * Analyses and equalizes an interleaved block in place.
     *
     * @param buffer      interleaved samples
     * @param numChannels channel count
     */
    public void processInterleaved(float[] buffer, int numChannels)
    {
        if (!prepared || buffer == null || numChannels < 1) return;
        int frames = buffer.length / numChannels;
        int used = Math.min(numChannels, planar.length);
        for (int c = 0; c < used; c++)
        {
            if (planar[c].length < frames) planar[c] = new double[frames];
            final double[] p = planar[c];
            for (int f = 0; f < frames; f++) p[f] = buffer[f * numChannels + c];
        }
        process(used == planar.length ? planar : java.util.Arrays.copyOf(planar, used), frames);
        for (int c = 0; c < used; c++)
        {
            final double[] p = planar[c];
            for (int f = 0; f < frames; f++) buffer[f * numChannels + c] = (float) p[f];
        }
    }

    // What is about to be taken of the programme into both readings, the
    // model running at each of its hops.
    private void feed(int offset, int count)
    {
        final double[][] buffer = current;
        final int nCh = currentChannels;
        // What is not a number neither is read nor enters the filter.
        for (double[] x : buffer)
            for (int k = 0; k < count; k++)
                if (!Double.isFinite(x[offset + k])) x[offset + k] = 0.0;
        int done = 0;
        while (done < count)
        {
            int chunk = Math.min(count - done, hop - hopCounter);
            for (int ch = 0; ch < nCh; ch++)
            {
                final double[] x = buffer[ch];
                final double[] r = ring[ch];
                int head = Math.min(chunk, fft - ringPos);
                System.arraycopy(x, offset + done, r, ringPos, head);
                System.arraycopy(x, offset + done + head, r, 0, chunk - head);
                analysis.feed(ch, x, offset + done, chunk);
            }
            ringPos = (ringPos + chunk) & (fft - 1);
            hopCounter += chunk;
            if (hopCounter >= hop)
            {
                hopCounter = 0;
                analyse(nCh);
            }
            done += chunk;
        }
    }

    // The moments of the bins into those of the bands, in tmpA and on.
    private void readBands(int lanes, boolean smoothed)
    {
        if (smoothed) envelope.computeInterleaved(lanes, moments);
        bands.bandPowersInterleaved(lanes, moments, bandMoments);
        for (int j = 0; j < lanes; j++)
        {
            final double[] to = j == 0 ? tmpA : (j == 1 ? tmpB : (j == 2 ? tmpC : tmpD));
            for (int b = 0; b < BANDS; b++) to[b] = bandMoments[lanes * b + j];
            AuditoryBands.smoothAcross(to, BANDS, 0.25);
        }
    }

    // One hop of the model: the broad balance and the loudness.
    private void analyse(int nCh)
    {
        for (int ch = 0; ch < nCh; ch++)
        {
            final double[] r = ring[ch], w = window, f = frame;
            int older = fft - ringPos;
            for (int k = 0; k < older; k++) f[k] = r[ringPos + k] * w[k];
            for (int k = 0; k < ringPos; k++) f[older + k] = r[k] * w[older + k];
            transform.forward(f, spectra[ch]);
        }
        // Band powers of the two channels and of their cross term, each from
        // a spectrum smoothed across frequency.
        final double[] s0 = spectra[0];
        final double[] s1 = spectra[nCh > 1 ? 1 : 0];
        final boolean focused = timeFocus;
        final double[] m = moments;
        if (focused)
        {
            // Through the focus: each channel's complex spectrum smoothed
            // across frequency, then the products.
            int other = nCh > 1 ? 1 : 0;
            focus.smooth(s0, focusRe[0], focusIm[0]);
            if (nCh > 1) focus.smooth(s1, focusRe[1], focusIm[1]);
            final double[] ar = focusRe[0], ai = focusIm[0], br = focusRe[other], bi = focusIm[other];
            for (int k = 0; k < bins; k++)
            {
                double scale = powerScale * focus.getGain(k);
                if (nCh > 1)
                {
                    m[MOMENTS * k] = scale * (ar[k] * ar[k] + ai[k] * ai[k]);
                    m[MOMENTS * k + 1] = scale * (br[k] * br[k] + bi[k] * bi[k]);
                    m[MOMENTS * k + 2] = scale * (ar[k] * br[k] + ai[k] * bi[k]);
                    m[MOMENTS * k + 3] = scale * (ai[k] * br[k] - ar[k] * bi[k]);
                }
                else
                {
                    m[k] = scale * (ar[k] * ar[k] + ai[k] * ai[k]);
                }
            }
        }
        else if (nCh > 1)
        {
            for (int k = 0; k < bins; k++)
            {
                double lRe = s0[2 * k], lIm = s0[2 * k + 1], rRe = s1[2 * k], rIm = s1[2 * k + 1];
                m[MOMENTS * k] = powerScale * (lRe * lRe + lIm * lIm);
                m[MOMENTS * k + 1] = powerScale * (rRe * rRe + rIm * rIm);
                m[MOMENTS * k + 2] = powerScale * (lRe * rRe + lIm * rIm);
                m[MOMENTS * k + 3] = powerScale * (lIm * rRe - lRe * rIm);
            }
        }
        else
        {
            for (int k = 0; k < bins; k++) m[k] = powerScale * (s0[2 * k] * s0[2 * k] + s0[2 * k + 1] * s0[2 * k + 1]);
        }
        readBands(nCh > 1 ? MOMENTS : 1, !focused);

        // The two smoothings in time: by band (both the band's own time,
        // longer in the low end) or a short integration per band and then a
        // response common to all.
        final boolean common = response == Response.COMMON;
        final double commonCoeff = Math.exp(-(double) hop / (0.001 * responseMs * fs));
        // The covariance in time; the dominant power.
        for (int b = 0; b < BANDS; b++)
        {
            double c = warm ? (common ? briefCoeff[b] : timeCoeff[b]) : 0.0;
            ll[b] = tmpA[b] + c * (ll[b] - tmpA[b]);
            if (nCh > 1)
            {
                rr[b] = tmpB[b] + c * (rr[b] - tmpB[b]);
                lr[b] = tmpC[b] + c * (lr[b] - tmpC[b]);
                li[b] = tmpD[b] + c * (li[b] - tmpD[b]);
                tmpA[b] = AuditoryScale.dominantPower(ll[b], rr[b], lr[b], li[b]);
            }
            else
            {
                tmpA[b] = ll[b];
            }
        }
        bands.specificLoudness(tmpA, tmpB);
        final boolean comp = levelComp;
        // The even programme: the same power in every band (the mean of this
        // programme's), and its loudness through the same model and the same
        // smoothing in time.
        double meanPower = 0.0;
        for (int b = 0; b < BANDS; b++) meanPower += tmpA[b];
        meanPower /= BANDS;
        java.util.Arrays.fill(tmpD, meanPower);
        bands.specificLoudness(tmpD, tmpC);
        for (int b = 0; b < BANDS; b++)
        {
            double c = warm ? (common ? commonCoeff : timeCoeff[b]) : 0.0;
            loud[b] = tmpB[b] + c * (loud[b] - tmpB[b]);
            evenLoud[b] = tmpC[b] + c * (evenLoud[b] - tmpC[b]);
        }
        warm = true;
        final boolean evenPower = reference == Reference.EVEN_POWER;
        final boolean local = localSlope;
        // Loudness in decibels of power, the trend, the two deviations.
        final double zLo = AuditoryScale.hzToBark(loHz), zHi = AuditoryScale.hzToBark(hiHz);
        // How audible each band is: its power over its threshold in quiet,
        // nothing under it, all of it from AUDIBLE_SPAN dB over. A band under
        // the threshold has a loudness of almost nothing, and its level would
        // read as a hole of tens of decibels: it neither shapes the trend nor
        // takes a correction.
        for (int b = 0; b < BANDS; b++)
        {
            exponent[b] = comp ? bands.getExponent(b) : MID_EXPONENT;
            steep[b] = exponent[b];
            if (local)
            {
                // d ln N / d ln P of N = (P + th)^a - th^a at the power this
                // band's loudness stands for: a far over the threshold, 1 under it.
                double a = bands.getExponent(b), th = bands.getThreshold(b);
                double whole = loud[b] + bands.getThresholdLoudness(b);   // (P + th)^a
                double p = Math.pow(whole, 1.0 / a) - th;                 // P
                steep[b] = loud[b] > 1e-30 && p > 0.0
                        ? clamp(a * p * whole / ((p + th) * loud[b]), exponent[b], 1.0) : 1.0;
            }
            level[b] = 10.0 * Math.log10(loud[b] + 1e-12) / exponent[b];
            // Against the even programme, the level is how far this one
            // stands from it in each band.
            if (evenPower) level[b] -= 10.0 * Math.log10(evenLoud[b] + 1e-12) / exponent[b];
            double over = 10.0 * Math.log10((tmpA[b] + 1e-30) / bands.getThreshold(b));
            audible[b] = clamp(over / AUDIBLE_SPAN, 0.0, 1.0);
            trend[b] = audible[b] * level[b];
            weighed[b] = audible[b];
            double z = bands.getBark(b);
            inRange[b] = clamp(z - zLo + 0.5, 0.0, 1.0) * clamp(zHi - z + 0.5, 0.0, 1.0);
            weight[b] = inRange[b] * audible[b];
        }
        // How far under the programme each band lies: one setFloor() dB under
        // the loudest audible band is no part of the balance to give back, and
        // it takes neither the balance nor the offset that keeps the loudness;
        // it counts in full from MASKED_SPAN dB over that.
        {
            double loudest = -1e9;
            for (int b = 0; b < BANDS; b++)
                if (audible[b] > 0.0) loudest = Math.max(loudest, level[b]);
            for (int b = 0; b < BANDS; b++)
                present[b] = clamp((level[b] - loudest + floor) / MASKED_SPAN, 0.0, 1.0);
        }
        // The trend across the bands, each weighed by how audible it is (a
        // normalized smoothing: the audible neighbours of a band decide it).
        AuditoryBands.smoothAcross(trend, BANDS, 0.9);
        AuditoryBands.smoothAcross(weighed, BANDS, 0.9);
        for (int b = 0; b < BANDS; b++)
            trend[b] = weighed[b] > 1e-9 ? trend[b] / weighed[b] : level[b];
        double meanDev = 0.0, wsum = 0.0;
        for (int b = 0; b < BANDS; b++)
        {
            double ref = tilt * centreOctave[b];
            tmpC[b] = trend[b] - ref;
            meanDev += weight[b] * tmpC[b];
            wsum += weight[b];
        }
        meanDev = wsum > 0.0 ? meanDev / wsum : 0.0;
        // What is lacking (under the reference) and what is in excess, each
        // with its share of the balance; the point between them moved by the bias.
        final double liftShare = balance * (1.0 + balanceAsymmetry) * raising, cutShare = balance * (1.0 - balanceAsymmetry) * lowering;
        final double rng = range, kn = knee, rk = rangeKnee, bias = balanceBias;
        final double midExponent = AuditoryScale.loudnessExponent(3300.0);
        // The limit of the range: hard, or the smaller of the correction and
        // the range through a hyperbola (set to pass through 0 and to settle
        // on the range).
        final double atZero = 0.5 * (rng - Math.sqrt(rng * rng + rk * rk));
        for (int b = 0; b < BANDS; b++)
        {
            // With the local slope, what a band lacks or has in excess is read with
            // the slope of its own loudness curve (never more than the exponent says).
            double ease = exponent[b] / steep[b];
            double global = ease * (tmpC[b] - meanDev - bias);
            // The balance by how present the band is (limited first, so a band
            // half way down to the floor takes half of it), then what the
            // detail moves in the band.
            double soft = Math.copySign(Math.sqrt(global * global + kn * kn) - kn, global);
            double c = limited(-(global < 0.0 ? liftShare : cutShare) * soft, rng, rk, atZero);
            if (present[b] < 1.0) c *= present[b];
            c = clamp(c * weight[b] + detailMean[b], -rng, rng);
            // The listening level: a fixed contour, whatever the programme.
            if (listening != 0.0)
                c = clamp(c + listening * (1.0 - midExponent / bands.getExponent(b)) * inRange[b], -rng, rng);
            corr[b] = c;
        }
        // The total loudness stays: every band in range takes the same
        // loudness ratio (by its weight), found with the limits in place: a
        // band at its limit or out of the range gives nothing, so the others
        // make up for it. The loudness after an offset rises with it; a few
        // steps on its logarithm (exact in one when no band is held) with a
        // bracket find where it meets the loudness before.
        double before = 0.0;
        for (int b = 0; b < BANDS; b++)
        {
            part[b] = ((b == 0 || b == BANDS - 1) ? 0.5 : 1.0) * loud[b];
            before += part[b];
        }
        double offset = 0.0, change = 0.0;
        if (before > 1e-20)
        {
            double under = -OFFSET_LIMIT, over = OFFSET_LIMIT;   // the offset lies between them
            boolean measured = false;   // whether `change` was read at the offset kept
            for (int step = 0; step < OFFSET_STEPS; step++)
            {
                double after = loudnessAt(offset, rng);
                change = Math.log(Math.max(after, 1e-300) / before);
                measured = true;
                if (Math.abs(change) < 1e-9) break;
                if (change > 0.0) over = offset; else under = offset;
                // Where every band that could move is at its limit there is
                // no slope: the offset is halfway back in the bracket.
                double slope = offsetSlope;
                double next = slope > 1e-30 * before ? offset - change * after / slope : over + 1.0;
                offset = next > under && next < over ? next : 0.5 * (under + over);
                measured = false;
            }
            // The share of the offset that is applied, by whether it raises or
            // lowers; the change is read where it lands.
            final double share = offset > 0.0 ? raising : lowering;
            if (share < 1.0)
            {
                offset *= share;
                measured = false;
            }
            if (!measured) change = Math.log(Math.max(loudnessAt(offset, rng), 1e-300) / before);
            change /= PER_DB;
        }
        loudnessChange = change;
        for (int b = 0; b < BANDS; b++)
        {
            corr[b] = clamp(corr[b] + offset * weight[b] * present[b] / steep[b], -rng, rng);
            // The broad curve: the band's, less what was the detail's.
            base[b] = corr[b] - detailMean[b];
            published[b] = corr[b];
        }
        // What the corrections do to the loudness: the share the raised bands
        // gain, the share the lowered ones lose, the total, and how far its
        // centre moves along the ear's scale.
        {
            double up = 0.0, down = 0.0, after = 0.0, centreBefore = 0.0, centreAfter = 0.0;
            for (int b = 0; b < BANDS; b++)
            {
                double l = part[b] * Math.exp(PER_DB * steep[b] * corr[b]);
                double d = l - part[b];
                if (d > 0.0) up += d; else down += d;
                after += l;
                centreBefore += part[b] * bands.getBark(b);
                centreAfter += l * bands.getBark(b);
            }
            boolean sounding = before > 1e-20 && after > 1e-20;
            raised = sounding ? up / before : 0.0;
            lowered = sounding ? down / before : 0.0;
            total = sounding ? 10.0 * Math.log10(before) : -200.0;
            centroidShift = sounding ? centreAfter / after - centreBefore / before : 0.0;
        }
    }

    private double offsetSlope = 0.0;   // d(loudness)/d(offset) of the last loudnessAt()

    // The loudness after an offset, with the limits in place; its slope in offsetSlope.
    private double loudnessAt(double offset, double rng)
    {
        double s = 0.0, slope = 0.0;
        for (int b = 0; b < BANDS; b++)
        {
            double raw = corr[b] + offset * weight[b] * present[b] / steep[b];
            double l = part[b] * Math.exp(PER_DB * steep[b] * clamp(raw, -rng, rng));
            s += l;
            if (raw > -rng && raw < rng) slope += l * PER_DB * weight[b] * present[b];
        }
        offsetSlope = slope;
        return s;
    }

    private static double limited(double c, double rng, double rangeKnee, double atZero)
    {
        if (!(rangeKnee > 0.0)) return clamp(c, -rng, rng);
        double a = Math.abs(c), past = a - rng;
        double rounded = 0.5 * (a + rng - Math.sqrt(past * past + rangeKnee * rangeKnee));
        return Math.copySign((rounded - atZero) * rng / (rng - atZero), c);
    }

    // -- The detail: the dips and the peaks over the whole band -----------------

    // The grid's maps onto the bands of the model.
    private void prepareDetail()
    {
        hopSeconds = filter.getHopSize() / fs;
        top = 0;
        for (int i = 0; i < POINTS; i++)
        {
            double hz = DetailFilter.frequency(i);
            if (hz < 0.45 * fs) top = i + 1;
            octave[i] = Math.log(hz / 1000.0) / Math.log(2.0);
            double at = clamp((AuditoryScale.hzToBark(hz) - bands.getBark(0)) / bands.getSpacing(), 0.0, BANDS - 1.0);
            int under = Math.min((int) at, BANDS - 2);
            double t = at - under;
            placeBand[i] = under;
            placePart[i] = t;
            // Catmull-Rom between the centres: the broad curve has no corners.
            placeWeight[4 * i] = 0.5 * t * ((2.0 - t) * t - 1.0);
            placeWeight[4 * i + 1] = 0.5 * (t * t * (3.0 * t - 5.0) + 2.0);
            placeWeight[4 * i + 2] = 0.5 * t * ((4.0 - 3.0 * t) * t + 1.0);
            placeWeight[4 * i + 3] = 0.5 * t * t * (t - 1.0);
            // Few bins at the bottom: the low end takes longer to settle.
            slowCoeff[i] = Math.exp(-hopSeconds / clamp(1.5 * Math.sqrt(100.0 / hz), SHORTEST_SMOOTHING, 2.0));
        }
        detailGlide = Math.exp(-hopSeconds / DETAIL_GLIDE_SECONDS);
        lastDetail = detail;
        refreshDetail();
    }

    // What follows the detail and the reference level: the reading, the sides, the thresholds.
    private void refreshDetail()
    {
        double width = lastDetail;
        analysis.setWidth(width);
        double perOctave = (POINTS - 1) * Math.log(2.0) / Math.log(1000.0);
        aside = Math.max(2, (int) lround(0.5 * width * perOctave));
        // The rule of the space between partials comes in from a sixth of an
        // octave down to a twelfth, so that a sweep of the detail has no step.
        gapShare = clamp(Math.log(FINEST_DIP / width) / Math.log(2.0), 0.0, 1.0);
        gapHalf = (int) lround(0.5 * FINEST_DIP * perOctave);
        acrossHalf = Math.max(1, (int) lround(width * perOctave / 8.0));
        sidePole = Math.exp(-1.0 / (Math.max(SIDE_OCTAVES, 2.0 * width) * perOctave));
        double bandShare = 0.5 * (Math.pow(2.0, 0.5 * width) - Math.pow(2.0, -0.5 * width))
                + 0.5 * (Math.pow(1000.0, 1.0 / (POINTS - 1)) - 1.0);
        for (int i = 0; i < POINTS; i++)
        {
            double hz = DetailFilter.frequency(i);
            int band = placeBand[i];
            // A point holds the power of its width (of the window's, where that
            // is wider); the threshold is that of a band of the model around it.
            double holds = Math.max(bandShare * hz, 1.5 / Math.max(analysis.getWindowSeconds(i), 1e-3));
            double modelBand = AuditoryScale.criticalBandwidth(hz) * bands.getSpacing() * modelNoiseBins;
            double a = 10.0 * Math.log10(bands.getThreshold(band) + 1e-30), b = 10.0 * Math.log10(bands.getThreshold(band + 1) + 1e-30);
            threshold[i] = a + placePart[i] * (b - a) - 10.0 * Math.log10(modelBand / holds);
            // A point stands for the hertz to the next: that share of what it holds.
            pointShare[i] = hz / holds;
            // And a band of the model sees a stretch around it: its envelope's
            // sixth of an octave, or the lobe of its window where that is wider.
            double reach = Math.max(hz * 0.5 * (Math.pow(2.0, 1.0 / 12.0) - Math.pow(2.0, -1.0 / 12.0)), 0.5 * modelNoiseBins * fs / fft);
            double last = POINTS - 1.0, perLog = last / Math.log(1000.0);
            int from = (int) lround(perLog * Math.log(Math.max(20.0, hz - reach) / 20.0));
            int to = (int) lround(Math.min(last, perLog * Math.log((hz + reach) / 20.0))) + 1;
            seenFrom[i] = Math.max(0, Math.min(from, i));
            seenTo[i] = Math.max(i + 1, Math.min(to, POINTS));
            seenShare[i] = hz / (2.0 * reach);
        }
    }

    private void resetDetail()
    {
        java.util.Arrays.fill(smoothPower, 0.0);
        java.util.Arrays.fill(seen, 0.0);
        java.util.Arrays.fill(detailGain, 0.0);
        java.util.Arrays.fill(base, 0.0);
        java.util.Arrays.fill(detailMean, 0.0);
    }

    // The curve of a hop: the broad balance between the bands' centres, and the detail.
    private void design(double[] curve)
    {
        readDetail();
        final double rng = range;
        for (int i = 0; i < POINTS; i++)
        {
            int under = placeBand[i];
            double before = base[under > 0 ? under - 1 : 0], after = base[Math.min(under + 2, BANDS - 1)];
            double broad = placeWeight[4 * i] * before + placeWeight[4 * i + 1] * base[under]
                         + placeWeight[4 * i + 2] * base[under + 1] + placeWeight[4 * i + 3] * after;
            // What the smooth line swings past its two bands is raised or
            // lowered like the rest.
            if (raising < 1.0 || lowering < 1.0)
            {
                double over = Math.max(base[under], base[under + 1]), below = Math.min(base[under], base[under + 1]);
                if (broad > over && raising < 1.0) broad = over + raising * (broad - over);
                else if (broad < below && lowering < 1.0) broad = below + lowering * (broad - below);
            }
            curve[i] = clamp(broad + detailGain[i], -rng, rng);
        }
    }

    // The dips and the peaks of a hop, and what they move in each band of the model.
    private void readDetail()
    {
        analysis.read(0, power);
        final boolean common = response == Response.COMMON;
        final double shared = common ? Math.exp(-hopSeconds / Math.max(SHORTEST_COMMON, 0.001 * responseMs)) : 0.0;
        for (int i = 0; i < top; i++)
        {
            double keep = common ? shared : slowCoeff[i];
            double p = Double.isFinite(power[i]) ? power[i] : 0.0;
            smoothPower[i] = p + keep * (smoothPower[i] - p);
            seen[i] = 1.0 + keep * (seen[i] - 1.0);   // of the smoothing filled: no rise out of the start
            power[i] = seen[i] > 0.0 ? smoothPower[i] / seen[i] : 0.0;
            levelDb[i] = DB_PER_LN * DspMath.fastLog(power[i] + 1e-30);
            heard[i] = clamp((levelDb[i] - threshold[i]) / AUDIBLE_SPAN, 0.0, 1.0);
        }
        java.util.Arrays.fill(asked, 0.0);
        final double fill = fillDips * raising, trim = trimPeaks * lowering;
        if (fill > 0.0 || trim > 0.0) readContrast(fill, trim);

        boolean moving = false;
        for (int i = 0; i < POINTS; i++)
        {
            double g = asked[i] + detailGlide * (detailGain[i] - asked[i]);
            if (asked[i] == 0.0 && Math.abs(g) < 1e-6) g = 0.0;
            detailGain[i] = g;
            moving = moving || g != 0.0;
        }
        java.util.Arrays.fill(detailMean, 0.0);
        if (!moving) return;
        // By the power it moves there, as the model reads it: a band of the
        // model sees what is around it through its window and its envelope,
        // so a gap beside a partial is as loud as the partial makes it, and
        // what raises the gap adds next to nothing.
        double pow = 0.0, change = 0.0;
        for (int i = 0; i < top; i++)
        {
            heldSum[i] = pow;
            movedSum[i] = change;
            double p = power[i] * pointShare[i];
            pow += p;
            change += p * (Math.exp(PER_DB * detailGain[i]) - 1.0);
        }
        heldSum[top] = pow;
        movedSum[top] = change;
        java.util.Arrays.fill(moved, 0.0);
        java.util.Arrays.fill(held, 0.0);
        for (int i = 0; i < top; i++)
        {
            double t = placePart[i];
            int first = Math.min(seenFrom[i], top), end = Math.min(seenTo[i], top);
            double share = seenShare[i];
            double around = share * (heldSum[end] - heldSum[first]), moves = share * (movedSum[end] - movedSum[first]);
            int under = placeBand[i];
            held[under] += (1.0 - t) * around;
            held[under + 1] += t * around;
            moved[under] += (1.0 - t) * moves;
            moved[under + 1] += t * moves;
        }
        AuditoryBands.smoothAcross(held, BANDS, READ_ACROSS);
        AuditoryBands.smoothAcross(moved, BANDS, READ_ACROSS);
        for (int b = 0; b < BANDS; b++)
            if (held[b] > 0.0) detailMean[b] = 10.0 * Math.log10(Math.max(1.0 + moved[b] / held[b], 1e-12));
    }

    private static double sideDb(double s, double w)
    {
        return QUARTER_DB_PER_LN * DspMath.fastLog(s / w + 1e-8);
    }

    // Each point against its two sides, in asked.
    private void readContrast(double fill, double trim)
    {
        final double[] db = levelDb;
        final double pole = sidePole;
        // Audible points alone, and none within the detail of the point itself.
        // A side is the mean of its loudness (the power to a quarter, as the
        // ear has it): the emptiness past a group of lines would pull a mean
        // of decibels under the gaps inside the group, and in a mean of
        // amplitudes a line as wide as the window would hide the one beside it.
        for (int i = 0; i < top; i++) quarter[i] = heard[i] * Math.sqrt(Math.sqrt(power[i]));
        double num = 0.0, den = 0.0;
        for (int i = 0; i < top; i++)
        {
            int j = i - aside;
            if (j >= 0)
            {
                num = pole * num + quarter[j];
                den = pole * den + heard[j];
            }
            lowerSide[i] = den > 1e-9 ? sideDb(num, den) : db[i];
        }
        num = 0.0;
        den = 0.0;
        for (int i = top - 1; i >= 0; i--)
        {
            int j = i + aside;
            if (j < top)
            {
                num = pole * num + quarter[j];
                den = pole * den + heard[j];
            }
            upperSide[i] = den > 1e-9 ? sideDb(num, den) : db[i];
        }

        double[] dips = db;
        if (gapShare > 0.0)
        {
            // A dip is looked for no finer than a sixth of an octave: what is
            // narrower between two partials is the space between them.
            double running = 0.0;
            for (int i = 0; i < top; i++)
            {
                sum[i] = running;
                running += power[i];
            }
            sum[top] = running;
            for (int i = 0; i < top; i++)
            {
                int first = Math.max(0, i - gapHalf), end = Math.min(top, i + gapHalf + 1);
                double around = (sum[end] - sum[first]) / (end - first);
                double over = DB_PER_LN * DspMath.fastLog(around + 1e-30) - db[i];
                gapLevel[i] = db[i] + gapShare * Math.max(0.0, over);
            }
            dips = gapLevel;
        }
        final double kn = contrastKnee;
        final double rng = range, rk = rangeKnee;
        final double atZero = 0.5 * (rng - Math.sqrt(rng * rng + rk * rk));
        final double from = Math.log(loHz / 1000.0) / Math.log(2.0);
        final double to = Math.log(hiHz / 1000.0) / Math.log(2.0);
        for (int i = 0; i < top; i++)
        {
            if (heard[i] == 0.0) continue;
            // The edges of the range are a third of an octave wide here (one
            // Bark would reach from 40 Hz to 90 Hz).
            double in = clamp(3.0 * (octave[i] - from) + 0.5, 0.0, 1.0) * clamp(3.0 * (to - octave[i]) + 0.5, 0.0, 1.0);
            if (in == 0.0) continue;
            double under = Math.min(0.0, dips[i] - Math.min(lowerSide[i], upperSide[i]));
            // Nor is that space cut with the partials beside it.
            double over = Math.max(0.0, db[i] - Math.max(lowerSide[i], upperSide[i]) - (dips[i] - db[i]));
            double low = clamp((EMPTY + under) / EMPTY_FADE, 0.0, 1.0);
            double c = -trim * bend(over, kn) - fill * low * bend(Math.max(under, -DIP_LIMIT), kn);
            c = limited(c, rng, rk, atZero);
            asked[i] = in * heard[i] * c;
        }
        // No finer than the detail: over an eighth of it on either side, a point
        // at least (a step of one point would ring between two of them).
        double running = 0.0;
        for (int i = 0; i < POINTS; i++)
        {
            sum[i] = running;
            running += asked[i];
        }
        sum[POINTS] = running;
        for (int i = 0; i < POINTS; i++)
        {
            int first = Math.max(0, i - acrossHalf), end = Math.min(POINTS, i + acrossHalf + 1);
            asked[i] = (sum[end] - sum[first]) / (end - first);
        }
    }

    private static double bend(double d, double kn)
    {
        return Math.copySign(Math.sqrt(d * d + kn * kn) - kn, d);
    }

    private static double clamp(double v, double lo, double hi)
    {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Rounds half away from zero. */
    private static long lround(double x)
    {
        return x < 0.0 ? -(long) Math.floor(-x + 0.5) : (long) Math.floor(x + 0.5);
    }
}
