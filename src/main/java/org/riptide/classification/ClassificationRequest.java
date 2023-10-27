package org.riptide.classification;

import com.google.common.base.MoreObjects;
import lombok.Builder;
import lombok.Data;

import java.net.InetAddress;
import java.util.Objects;

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
