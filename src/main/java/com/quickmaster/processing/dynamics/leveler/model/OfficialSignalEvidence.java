package com.quickmaster.processing.dynamics.leveler.model;

import java.nio.charset.StandardCharsets;

/** Observed finite/tagged reading; structural validity never grants official authority. */
public final class OfficialSignalEvidence
{
    private final String setId;
    private final String setVersion;
    private final int caseNumber;
    private final String signalId;
    private final String signalSha256;
    private final int sampleRateHz;
    private final int channels;
    private final ReadingKind readingKind;
    private final double expectedLufs;
    private final double measuredLufs;
    private final double toleranceLu;
    private final ChannelLayout channelLayout;
    private final ReadoutMode readoutMode;
    private final LoudnessValueKind expectedKind;
    private final LoudnessValueKind measuredKind;
    private final double minimumLufs;
    private final long sourceFramesConsumed;
    private final long firstReadoutEndFrame;
    private final long lastReadoutEndFrame;
    private final long readoutCount;
    private final long minEndFrame;
    private final long maxEndFrame;
    private final long completeGatingBlocks;
    private final long absoluteGateBlocks;
    private final long relativeGateBlocks;
    private final int resetCount;
    private final boolean eofReached;

    public OfficialSignalEvidence(String setId,
        String setVersion,
        int caseNumber,
        String signalId,
        String signalSha256,
        int sampleRateHz,
        int channels,
        ReadingKind readingKind,
        double expectedLufs,
        double measuredLufs,
        double toleranceLu,
        ChannelLayout channelLayout,
        ReadoutMode readoutMode,
        LoudnessValueKind expectedKind,
        LoudnessValueKind measuredKind,
        double minimumLufs,
        long sourceFramesConsumed,
        long firstReadoutEndFrame,
        long lastReadoutEndFrame,
        long readoutCount,
        long minEndFrame,
        long maxEndFrame,
        long completeGatingBlocks,
        long absoluteGateBlocks,
        long relativeGateBlocks,
        int resetCount,
        boolean eofReached)
    {
        if (caseNumber < 1 || sampleRateHz < 1 || channels < 1 || channelLayout == null
                || channelLayout.channels() != channels || readingKind == null || readoutMode == null
                || expectedKind == null || !Double.isFinite(expectedLufs)
                || !Double.isFinite(toleranceLu) || toleranceLu < 0.0d
                || !sha256(signalSha256) || !ascii(signalId, 1, 128)
                || !(setId != null && (setId.equals("ITU-R-BS.2217-1") || setId.equals("EBU-TECH-3341-V4")))
                || !(setVersion != null && (setVersion.equals("BS.2217-1") || setVersion.equals("LTS-5.0")))
                || (setId.equals("ITU-R-BS.2217-1") != setVersion.equals("BS.2217-1"))
                || (expectedKind == LoudnessValueKind.NO_LOUDNESS
                    && (Double.doubleToRawLongBits(expectedLufs) != 0L || Double.doubleToRawLongBits(toleranceLu) != 0L)))
            throw new IllegalArgumentException("Invalid official reading structure.");
        if (measuredKind == null || !Double.isFinite(measuredLufs) || !Double.isFinite(minimumLufs)
                || sourceFramesConsumed < 0L || firstReadoutEndFrame < 0L
                || lastReadoutEndFrame < firstReadoutEndFrame || lastReadoutEndFrame > sourceFramesConsumed
                || readoutCount < 0L || minEndFrame < firstReadoutEndFrame || minEndFrame > lastReadoutEndFrame
                || maxEndFrame < firstReadoutEndFrame || maxEndFrame > lastReadoutEndFrame
                || completeGatingBlocks < 0L || absoluteGateBlocks < 0L || relativeGateBlocks < 0L
                || absoluteGateBlocks > completeGatingBlocks || relativeGateBlocks > absoluteGateBlocks
                || resetCount < 0 || (measuredKind == LoudnessValueKind.NO_LOUDNESS
                    && (Double.doubleToRawLongBits(measuredLufs) != 0L || Double.doubleToRawLongBits(minimumLufs) != 0L)))
            throw new IllegalArgumentException("Invalid observed readout structure.");
        this.setId = setId.equals("ITU-R-BS.2217-1") ? "ITU-R-BS.2217-1" : "EBU-TECH-3341-V4";
        this.setVersion = setVersion.equals("BS.2217-1") ? "BS.2217-1" : "LTS-5.0";
        this.caseNumber = caseNumber;
        this.signalId = new String(signalId.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
        this.signalSha256 = new String(signalSha256.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
        this.sampleRateHz = sampleRateHz;
        this.channels = channels;
        this.readingKind = readingKind;
        this.expectedLufs = expectedLufs;
        this.measuredLufs = measuredLufs;
        this.toleranceLu = toleranceLu;
        this.channelLayout = channelLayout;
        this.readoutMode = readoutMode;
        this.expectedKind = expectedKind;
        this.measuredKind = measuredKind;
        this.minimumLufs = minimumLufs;
        this.sourceFramesConsumed = sourceFramesConsumed;
        this.firstReadoutEndFrame = firstReadoutEndFrame;
        this.lastReadoutEndFrame = lastReadoutEndFrame;
        this.readoutCount = readoutCount;
        this.minEndFrame = minEndFrame;
        this.maxEndFrame = maxEndFrame;
        this.completeGatingBlocks = completeGatingBlocks;
        this.absoluteGateBlocks = absoluteGateBlocks;
        this.relativeGateBlocks = relativeGateBlocks;
        this.resetCount = resetCount;
        this.eofReached = eofReached;
    }

    public String setId() { return setId; }
    public String setVersion() { return setVersion; }
    public int caseNumber() { return caseNumber; }
    public String signalId() { return signalId; }
    public String signalSha256() { return signalSha256; }
    public int sampleRateHz() { return sampleRateHz; }
    public int channels() { return channels; }
    public ReadingKind readingKind() { return readingKind; }
    public double expectedLufs() { return expectedLufs; }
    public double measuredLufs() { return measuredLufs; }
    public double toleranceLu() { return toleranceLu; }
    public ChannelLayout channelLayout() { return channelLayout; }
    public ReadoutMode readoutMode() { return readoutMode; }
    public LoudnessValueKind expectedKind() { return expectedKind; }
    public LoudnessValueKind measuredKind() { return measuredKind; }
    public double minimumLufs() { return minimumLufs; }
    public long sourceFramesConsumed() { return sourceFramesConsumed; }
    public long firstReadoutEndFrame() { return firstReadoutEndFrame; }
    public long lastReadoutEndFrame() { return lastReadoutEndFrame; }
    public long readoutCount() { return readoutCount; }
    public long minEndFrame() { return minEndFrame; }
    public long maxEndFrame() { return maxEndFrame; }
    public long completeGatingBlocks() { return completeGatingBlocks; }
    public long absoluteGateBlocks() { return absoluteGateBlocks; }
    public long relativeGateBlocks() { return relativeGateBlocks; }
    public int resetCount() { return resetCount; }
    public boolean eofReached() { return eofReached; }

    private static boolean ascii(String value, int min, int max)
    {
        if (value == null || value.length() < min || value.length() > max) return false;
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (c < 32 || c > 126 || c == '"' || c == '\\') return false;
        }
        return true;
    }

    private static boolean sha256(String value)
    {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < 64; i++)
        {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }
}
