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

    public static Builder from(ClassificationRequest source) {
        return builder()
                .withSrcPort(source.srcPort)
                .withDstPort(source.dstPort)
                .withProtocol(source.protocol)
                .withLocation(source.location)
                .withDstAddress(source.dstAddress)
                .withSrcAddress(source.srcAddress)
                .withExporterAddress(source.exporterAddress);
    }

    public static final class Builder {
        private Builder() {

        }

        public Builder withSrcAddress(String address) {
            return this.withSrcAddress(IpAddr.of(address));
        }

        public Builder withSrcAddress(IpAddr srcAddress) {
            this.srcAddress = srcAddress;
            return this;
        }

        public Builder withDstAddress(String address) {
            return this.withDstAddress(IpAddr.of(address));
        }

        public Builder withDstAddress(IpAddr ipAddr) {
            this.dstAddress = ipAddr;
            return this;
        }
    }
}
