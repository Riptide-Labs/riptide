package org.riptide.flows.repository.elastic.doc;


import com.google.gson.annotations.SerializedName;
import org.riptide.flows.Flow;

public enum SamplingAlgorithm {
    @SerializedName("Unassigned")
    Unassigned,
    @SerializedName("SystematicCountBasedSampling")
    SystematicCountBasedSampling,
    @SerializedName("SystematicTimeBasedSampling")
    SystematicTimeBasedSampling,
    @SerializedName("RandomNoutOfNSampling")
    RandomNoutOfNSampling,
    @SerializedName("UniformProbabilisticSampling")
    UniformProbabilisticSampling,
    @SerializedName("PropertyMatchFiltering")
    PropertyMatchFiltering,
    @SerializedName("HashBasedFiltering")
    HashBasedFiltering,
    @SerializedName("FlowStateDependentIntermediateFlowSelectionProcess")
    FlowStateDependentIntermediateFlowSelectionProcess;

    public static SamplingAlgorithm from(final Flow.SamplingAlgorithm samplingAlgorithm) {
        return switch (samplingAlgorithm) {
            case null -> Unassigned;
            case Unassigned -> Unassigned;
            case SystematicCountBasedSampling -> SystematicCountBasedSampling;
            case SystematicTimeBasedSampling -> SystematicTimeBasedSampling;
            case RandomNOutOfNSampling -> RandomNoutOfNSampling;
            case UniformProbabilisticSampling -> UniformProbabilisticSampling;
            case PropertyMatchFiltering -> PropertyMatchFiltering;
            case HashBasedFiltering -> HashBasedFiltering;
            case FlowStateDependentIntermediateFlowSelectionProcess ->
                    FlowStateDependentIntermediateFlowSelectionProcess;
            default -> throw new IllegalArgumentException("Unknown sampling algorithm: " + samplingAlgorithm.name());
        };
    }
}
