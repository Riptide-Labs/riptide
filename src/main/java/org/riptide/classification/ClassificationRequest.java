package org.riptide.classification;

import lombok.Builder;
import lombok.Data;

@Data
@Builder(builderClassName = "Builder", setterPrefix = "with")
public class ClassificationRequest {
    private final String location;
    private final Protocol protocol;
    private final Integer dstPort;
    private final IpAddr dstAddress;
    private final Integer srcPort;
    private final IpAddr srcAddress;
    private final IpAddr exporterAddress;
}
