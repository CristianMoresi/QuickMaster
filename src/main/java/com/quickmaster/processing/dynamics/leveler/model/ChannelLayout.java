package com.quickmaster.processing.dynamics.leveler.model;

/** Source-channel order and BS.1770 power weights; user PCM remains mono/stereo. */
public enum ChannelLayout
{
    MONO_MAIN,
    STEREO_LR,
    SURROUND_5_0,
    SURROUND_5_1;

    public int channels()
    {
        if (this == MONO_MAIN) return 1;
        if (this == STEREO_LR) return 2;
        if (this == SURROUND_5_0) return 5;
        return 6;
    }

    public double powerWeight(int channelIndex)
    {
        if (channelIndex < 0 || channelIndex >= channels())
            throw new IllegalArgumentException("Channel index is outside its layout.");
        if (this == SURROUND_5_1)
        {
            if (channelIndex == 3) return 0.0d;
            if (channelIndex >= 4) return 1.41d;
        }
        else if (this == SURROUND_5_0 && channelIndex >= 3) return 1.41d;
        return 1.0d;
    }
}
