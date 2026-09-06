/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

import lombok.Data;
import lombok.Getter;

@Data
// Fully qualified, and the import dropped: lombok 1.18.48 makes builderClassName = "Builder" an
// error when the annotation is imported, because the nested Builder below shadows the
// lombok.Builder the annotation refers to (projectlombok#3857). Qualifying is the fix lombok's own
// error message offers first, and it keeps DefaultRule.Builder — renaming the builder would change
// a type this codebase and its tests name directly.
@lombok.Builder(builderClassName = "Builder", setterPrefix = "with")
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
