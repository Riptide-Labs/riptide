package org.riptide.classification;

import com.google.common.base.MoreObjects;

import java.net.InetAddress;
import java.util.Objects;

public class ClassificationRequest {

    private final String location;
    private final Protocol protocol;
    private final Integer dstPort;
    private final IpAddr dstAddress;
    private final Integer srcPort;
    private final IpAddr srcAddress;
    private final IpAddr exporterAddress;

    private ClassificationRequest(final Builder builder) {
        this.location = Objects.requireNonNull(builder.location);
        this.srcPort = Objects.requireNonNull(builder.srcPort);
        this.srcAddress = Objects.requireNonNull(builder.srcAddress);
        this.dstPort = Objects.requireNonNull(builder.dstPort);
        this.dstAddress = Objects.requireNonNull(builder.dstAddress);
        this.protocol = Objects.requireNonNull(builder.protocol);
        this.exporterAddress = Objects.requireNonNull(builder.exporterAddress);
    }

    public String getLocation() {
        return this.location;
    }

    public Protocol getProtocol() {
        return this.protocol;
    }

    public Integer getDstPort() {
        return this.dstPort;
    }

    public IpAddr getDstAddress() {
        return this.dstAddress;
    }

    public Integer getSrcPort() {
        return this.srcPort;
    }

    public IpAddr getSrcAddress() {
        return this.srcAddress;
    }

    public IpAddr getExporterAddress() {
        return this.exporterAddress;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClassificationRequest that = (ClassificationRequest) o;
        return Objects.equals(this.location, that.location)
                && Objects.equals(this.protocol, that.protocol)
                && Objects.equals(this.dstPort, that.dstPort)
                && Objects.equals(this.dstAddress, that.dstAddress)
                && Objects.equals(this.srcPort, that.srcPort)
                && Objects.equals(this.srcAddress, that.srcAddress)
                && Objects.equals(this.exporterAddress, that.exporterAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.location,
                this.protocol,
                this.dstPort,
                this.dstAddress,
                this.srcPort,
                this.srcAddress,
                this.exporterAddress);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("location", this.location)
                .add("protocol", this.protocol)
                .add("dstPort", this.dstPort)
                .add("dstAddress", this.dstAddress)
                .add("srcPort", this.srcPort)
                .add("srcAddress", this.srcAddress)
                .add("exporterAddress", this.exporterAddress)
                .toString();
    }

    public static class Builder {
        private String location;
        private Protocol protocol;
        private Integer dstPort;
        private IpAddr dstAddress;
        private Integer srcPort;
        private IpAddr srcAddress;
        private IpAddr exporterAddress;

        private Builder() {
        }

        public Builder withLocation(final String location) {
            this.location = location;
            return this;
        }

        public Builder withProtocol(final Protocol protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder withProtocol(final Integer protocol) {
            return this.withProtocol(Protocols.getProtocol(protocol));
        }

        public Builder withDstPort(final Integer dstPort) {
            this.dstPort = dstPort;
            return this;
        }

        public Builder withDstAddress(final IpAddr dstAddress) {
            this.dstAddress = dstAddress;
            return this;
        }

        public Builder withDstAddress(final InetAddress dstAddress) {
            return this.withDstAddress(IpAddr.of(dstAddress));
        }

        public Builder withSrcPort(final Integer srcPort) {
            this.srcPort = srcPort;
            return this;
        }

        public Builder withSrcAddress(final IpAddr srcAddress) {
            this.srcAddress = srcAddress;
            return this;
        }

        public Builder withSrcAddress(final InetAddress srcAddress) {
            return this.withSrcAddress(IpAddr.of(srcAddress));
        }

        public Builder withExporterAddress(final IpAddr exporterAddress) {
            this.exporterAddress = exporterAddress;
            return this;
        }

        public Builder withExporterAddress(final InetAddress exporterAddress) {
            return this.withExporterAddress(IpAddr.of(exporterAddress));
        }

        public ClassificationRequest build() {
            return new ClassificationRequest(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
