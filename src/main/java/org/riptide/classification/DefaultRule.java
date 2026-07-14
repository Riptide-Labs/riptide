/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;

@Data
@Builder(builderClassName = "Builder", setterPrefix = "with")
public class DefaultRule implements Rule {
    @Getter(onMethod_ = @Override)
    private String name;
    @Getter(onMethod_ = @Override)
    private String dstAddress;
    @Getter(onMethod_ = @Override)
    private String dstPort;
    @Getter(onMethod_ = @Override)
    private String srcPort;
    @Getter(onMethod_ = @Override)
    private String srcAddress;
    @Getter(onMethod_ = @Override)
    private String protocol;
    @Getter(onMethod_ = @Override)
    private String exporterFilter;
    @Getter(onMethod_ = @Override)
    private int groupPosition;
    @Getter(onMethod_ = @Override)
    private int position;
    @Getter(onMethod_ = @Override)
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
