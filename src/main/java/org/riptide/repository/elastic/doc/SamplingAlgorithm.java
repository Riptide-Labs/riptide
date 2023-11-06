package org.riptide.repository.elastic.doc;


import com.google.gson.annotations.SerializedName;

public enum SamplingAlgorithm {
    @SerializedName("Unassigned")
    Unassigned,
    @SerializedName("SystematicCountBasedSampling")
    SystematicCountBasedSampling,
    @SerializedName("SystematicTimeBasedSampling")
    SystematicTimeBasedSampling,
    @SerializedName("RandomNOutOfNSampling")
    RandomNOutOfNSampling,
    @SerializedName("UniformProbabilisticSampling")
    UniformProbabilisticSampling,
    @SerializedName("PropertyMatchFiltering")
    PropertyMatchFiltering,
    @SerializedName("HashBasedFiltering")
    HashBasedFiltering,
    @SerializedName("FlowStateDependentIntermediateFlowSelectionProcess")
    FlowStateDependentIntermediateFlowSelectionProcess;
}
