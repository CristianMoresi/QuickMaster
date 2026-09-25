package com.quickmaster.processing.dynamics.leveler.model;

/** Complete bounded M-004 evidence graph; it cannot be remapped or published. */
public final class ShadowAnalysisCache
{
    private final AudioFormat format;
    private final byte[] pcmFingerprintSha256;
    private final String algorithmId;
    private final String profileId;
    private final LoudnessTimeline loudness;
    private final FeatureTimeline features;
    private final SegmentLayout layout;
    private final FrozenList<SegmentDescriptor> descriptors;
    private final FrozenList<ProtectionDecision> protections;
    private final SimilarityMatrix similarity;
    private final GroupingResult grouping;
    private final ReferencePlan referencePlan;
    private final ComparisonTimeline comparison;

    public ShadowAnalysisCache(AudioFormat format,
                               byte[] pcmFingerprintSha256,
                               String algorithmId,
                               String profileId,
                               LoudnessTimeline loudness,
                               FeatureTimeline features,
                               SegmentLayout layout,
                               FrozenList<SegmentDescriptor> descriptors,
                               FrozenList<ProtectionDecision> protections,
                               SimilarityMatrix similarity,
                               GroupingResult grouping,
                               ReferencePlan referencePlan)
    {
        this(format, pcmFingerprintSha256, algorithmId, profileId, loudness, features, layout,
                descriptors, protections, similarity, grouping, referencePlan, null, false);
    }

    public ShadowAnalysisCache(AudioFormat format,
                               byte[] pcmFingerprintSha256,
                               String algorithmId,
                               String profileId,
                               LoudnessTimeline loudness,
                               FeatureTimeline features,
                               SegmentLayout layout,
                               FrozenList<SegmentDescriptor> descriptors,
                               FrozenList<ProtectionDecision> protections,
                               SimilarityMatrix similarity,
                               GroupingResult grouping,
                               ReferencePlan referencePlan,
                               ComparisonTimeline comparison)
    {
        this(format, pcmFingerprintSha256, algorithmId, profileId, loudness, features, layout,
                descriptors, protections, similarity, grouping, referencePlan, comparison, true);
    }

    private ShadowAnalysisCache(AudioFormat format,
                                byte[] pcmFingerprintSha256,
                                String algorithmId,
                                String profileId,
                                LoudnessTimeline loudness,
                                FeatureTimeline features,
                                SegmentLayout layout,
                                FrozenList<SegmentDescriptor> descriptors,
                                FrozenList<ProtectionDecision> protections,
                                SimilarityMatrix similarity,
                                GroupingResult grouping,
                                ReferencePlan referencePlan,
                                ComparisonTimeline comparison,
                                boolean requireComparison)
    {
        if (format == null || pcmFingerprintSha256 == null || pcmFingerprintSha256.length != 32
                || algorithmId == null || algorithmId.isEmpty() || profileId == null || profileId.isEmpty()
                || loudness == null || features == null || layout == null || descriptors == null
                || protections == null || similarity == null || grouping == null || referencePlan == null
                || descriptors.size() != protections.size() || descriptors.size() != referencePlan.size()
                || descriptors.size() != similarity.segmentCount())
        {
            throw new IllegalArgumentException("Invalid shadow cache.");
        }
        if (requireComparison && (comparison == null || comparison.format() != format
                || !"QM-LEVELER-S001-COMPARISON-V2".equals(algorithmId)
                || !"QM-LEVELER-V2".equals(profileId)))
            throw new IllegalArgumentException("Invalid V2 comparison cache identity.");
        this.format = format;
        this.pcmFingerprintSha256 = new byte[32];
        System.arraycopy(pcmFingerprintSha256, 0, this.pcmFingerprintSha256, 0, 32);
        this.algorithmId = algorithmId;
        this.profileId = profileId;
        this.loudness = loudness;
        this.features = features;
        this.layout = layout;
        this.descriptors = descriptors;
        this.protections = protections;
        this.similarity = similarity;
        this.grouping = grouping;
        this.referencePlan = referencePlan;
        this.comparison = comparison;
    }

    public AudioFormat format() { return format; }
    public String algorithmId() { return algorithmId; }
    public String profileId() { return profileId; }
    public LoudnessTimeline loudness() { return loudness; }
    public FeatureTimeline features() { return features; }
    public SegmentLayout layout() { return layout; }
    public FrozenList<SegmentDescriptor> descriptors() { return descriptors; }
    public FrozenList<ProtectionDecision> protections() { return protections; }
    public SimilarityMatrix similarity() { return similarity; }
    public GroupingResult grouping() { return grouping; }
    public ReferencePlan referencePlan() { return referencePlan; }
    public ComparisonTimeline comparison() { return comparison; }

    public byte[] copyPcmFingerprintSha256()
    {
        byte[] copy = new byte[32];
        System.arraycopy(pcmFingerprintSha256, 0, copy, 0, 32);
        return copy;
    }
}
