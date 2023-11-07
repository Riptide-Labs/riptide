package org.riptide.classification;

import lombok.Builder;
import lombok.Data;

@Data
@Builder(builderClassName = "Builder", setterPrefix = "with")
public class DefaultRule implements Rule {
    private String name;
    private String dstAddress;
    private String dstPort;
    private String srcPort;
    private String srcAddress;
    private String protocol;
    private String exporterFilter;
    private int groupPosition;
    private int position;
    private boolean omnidirectional;

    public static final class Builder {

        private Builder() {

        }

        public Builder withDstPort(String port) {
            this.dstPort = port;
            return this;
        }

        public Builder withSrcPort(String port) {
            this.srcPort = port;
            return this;
        }

        public Builder withDstPort(int port) {
            this.dstPort = Integer.toString(port);
            return this;
        }

        public Builder withSrcPort(int port) {
            this.srcPort = Integer.toString(port);
            return this;
        }
    }

}
