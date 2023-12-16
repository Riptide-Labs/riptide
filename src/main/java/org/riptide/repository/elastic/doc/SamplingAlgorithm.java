package org.riptide.repository.elastic.doc;


import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;

public enum SamplingAlgorithm {
    @SerializedName("Unassigned")
    @JsonProperty("Unassigned")
    Unassigned,
    @SerializedName("SystematicCountBasedSampling")
    @JsonProperty("SystematicCountBasedSampling")
    SystematicCountBasedSampling,
    @SerializedName("SystematicTimeBasedSampling")
    @JsonProperty("SystematicTimeBasedSampling")
    SystematicTimeBasedSampling,
    @SerializedName("RandomNOutOfNSampling")
    @JsonProperty("RandomNOutOfNSampling")
    RandomNOutOfNSampling,
    @SerializedName("UniformProbabilisticSampling")
    @JsonProperty("UniformProbabilisticSampling")
    UniformProbabilisticSampling,
    @SerializedName("PropertyMatchFiltering")
    @JsonProperty("PropertyMatchFiltering")
    PropertyMatchFiltering,
    @SerializedName("HashBasedFiltering")
    @JsonProperty("HashBasedFiltering")
    HashBasedFiltering,
    @SerializedName("FlowStateDependentIntermediateFlowSelectionProcess")
    @JsonProperty("FlowStateDependentIntermediateFlowSelectionProcess")
    FlowStateDependentIntermediateFlowSelectionProcess;
}
