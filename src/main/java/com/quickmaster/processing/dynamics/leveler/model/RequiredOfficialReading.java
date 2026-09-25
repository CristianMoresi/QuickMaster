package com.quickmaster.processing.dynamics.leveler.model;

import java.nio.charset.StandardCharsets;

/** One literal, statically pinned official file/readout requirement. */
public final class RequiredOfficialReading
{
    private final String setId;
    private final String setVersion;
    private final int caseNumber;
    private final String signalId;
    private final String signalSha256;
    private final int sampleRateHz;
    private final int channels;
    private final ChannelLayout channelLayout;
    private final ReadingKind readingKind;
    private final ReadoutMode readoutMode;
    private final LoudnessValueKind expectedKind;
    private final double expectedLufs;
    private final double toleranceLu;
    private final int bitsPerSample;
    private final int waveFormatTag;
    private final int channelMask;
    private final int blockAlign;
    private final long sourceFrames;
    private final long fileBytes;
    private final long dataBytes;
    private final long riffDeclaredEnd;
    private final long firstReadoutEndFrame;
    private final long lastReadoutEndFrame;
    private final long readoutCount;
    private final long completeGatingBlocks;

    public RequiredOfficialReading(String setId,
        String setVersion,
        int caseNumber,
        String signalId,
        String signalSha256,
        int sampleRateHz,
        int channels,
        ChannelLayout channelLayout,
        ReadingKind readingKind,
        ReadoutMode readoutMode,
        LoudnessValueKind expectedKind,
        double expectedLufs,
        double toleranceLu,
        int bitsPerSample,
        int waveFormatTag,
        int channelMask,
        int blockAlign,
        long sourceFrames,
        long fileBytes,
        long dataBytes,
        long riffDeclaredEnd,
        long firstReadoutEndFrame,
        long lastReadoutEndFrame,
        long readoutCount,
        long completeGatingBlocks)
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
        if ((bitsPerSample != 16 && bitsPerSample != 24) || (waveFormatTag != 1 && waveFormatTag != 65534)
                || channelMask < 0 || blockAlign != channels * (bitsPerSample / 8)
                || sourceFrames < 1L || fileBytes < 44L || dataBytes != Math.multiplyExact(sourceFrames, blockAlign)
                || riffDeclaredEnd < 44L || riffDeclaredEnd > fileBytes || firstReadoutEndFrame < 1L
                || lastReadoutEndFrame < firstReadoutEndFrame || lastReadoutEndFrame > sourceFrames
                || readoutCount < 1L || completeGatingBlocks < 0L)
            throw new IllegalArgumentException("Invalid required container/protocol.");
        this.setId = setId.equals("ITU-R-BS.2217-1") ? "ITU-R-BS.2217-1" : "EBU-TECH-3341-V4";
        this.setVersion = setVersion.equals("BS.2217-1") ? "BS.2217-1" : "LTS-5.0";
        this.caseNumber = caseNumber;
        this.signalId = signalId;
        this.signalSha256 = signalSha256;
        this.sampleRateHz = sampleRateHz;
        this.channels = channels;
        this.channelLayout = channelLayout;
        this.readingKind = readingKind;
        this.readoutMode = readoutMode;
        this.expectedKind = expectedKind;
        this.expectedLufs = expectedLufs;
        this.toleranceLu = toleranceLu;
        this.bitsPerSample = bitsPerSample;
        this.waveFormatTag = waveFormatTag;
        this.channelMask = channelMask;
        this.blockAlign = blockAlign;
        this.sourceFrames = sourceFrames;
        this.fileBytes = fileBytes;
        this.dataBytes = dataBytes;
        this.riffDeclaredEnd = riffDeclaredEnd;
        this.firstReadoutEndFrame = firstReadoutEndFrame;
        this.lastReadoutEndFrame = lastReadoutEndFrame;
        this.readoutCount = readoutCount;
        this.completeGatingBlocks = completeGatingBlocks;
    }

    public String setId() { return setId; }
    public String setVersion() { return setVersion; }
    public int caseNumber() { return caseNumber; }
    public String signalId() { return signalId; }
    public String signalSha256() { return signalSha256; }
    public int sampleRateHz() { return sampleRateHz; }
    public int channels() { return channels; }
    public ChannelLayout channelLayout() { return channelLayout; }
    public ReadingKind readingKind() { return readingKind; }
    public ReadoutMode readoutMode() { return readoutMode; }
    public LoudnessValueKind expectedKind() { return expectedKind; }
    public double expectedLufs() { return expectedLufs; }
    public double toleranceLu() { return toleranceLu; }
    public int bitsPerSample() { return bitsPerSample; }
    public int waveFormatTag() { return waveFormatTag; }
    public int channelMask() { return channelMask; }
    public int blockAlign() { return blockAlign; }
    public long sourceFrames() { return sourceFrames; }
    public long fileBytes() { return fileBytes; }
    public long dataBytes() { return dataBytes; }
    public long riffDeclaredEnd() { return riffDeclaredEnd; }
    public long firstReadoutEndFrame() { return firstReadoutEndFrame; }
    public long lastReadoutEndFrame() { return lastReadoutEndFrame; }
    public long readoutCount() { return readoutCount; }
    public long completeGatingBlocks() { return completeGatingBlocks; }

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

