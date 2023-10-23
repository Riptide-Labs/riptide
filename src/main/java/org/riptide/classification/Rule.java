package org.riptide.classification;

import com.google.common.base.Strings;

public interface Rule {

    String getName();

    String getDstAddress();

    String getDstPort();

    String getSrcPort();

    String getSrcAddress();

    String getProtocol();

    String getExporterFilter();

    int getGroupPosition();

    /**
     * Defines the order in which the rules are evaluated. Lower positions go first
     */
    int getPosition();

    boolean isOmnidirectional();

    default boolean hasProtocolDefinition() {
        return isDefined(getProtocol());
    }

    default boolean hasDstAddressDefinition() {
        return isDefined(getDstAddress());
    }

    default boolean hasDstPortDefinition() {
        return isDefined(getDstPort());
    }

    default boolean hasSrcAddressDefinition() {
        return isDefined(getSrcAddress());
    }

    default boolean hasSrcPortDefinition() {
        return isDefined(getSrcPort());
    }

    default boolean hasExportFilterDefinition() {
        return isDefined(getExporterFilter());
    }

    default boolean canBeReversed() {
        return isOmnidirectional() &&
                (hasSrcPortDefinition() || hasSrcAddressDefinition() || hasDstPortDefinition() || hasDstAddressDefinition());
    }

    default boolean hasDefinition() {
        return hasProtocolDefinition()
                || hasDstAddressDefinition()
                || hasDstPortDefinition()
                || hasSrcAddressDefinition()
                || hasSrcPortDefinition()
                || hasExportFilterDefinition();
    }

    default Rule reversedRule() {
        return new Rule() {
            @Override
            public String getName() {
                return Rule.this.getName();
            }

            @Override
            public String getDstAddress() {
                return Rule.this.getSrcAddress();
            }

            @Override
            public String getSrcAddress() {
                return Rule.this.getDstAddress();
            }

            @Override
            public String getDstPort() {
                return Rule.this.getSrcPort();
            }

            @Override
            public String getSrcPort() {
                return Rule.this.getDstPort();
            }

            @Override
            public String getProtocol() {
                return Rule.this.getProtocol();
            }

            @Override
            public String getExporterFilter() {
                return Rule.this.getExporterFilter();
            }

            @Override
            public int getGroupPosition() {
                return Rule.this.getGroupPosition();
            }

            @Override
            public int getPosition() {
                return Rule.this.getPosition();
            }

            @Override
            public boolean isOmnidirectional() {
                return Rule.this.isOmnidirectional();
            }
        };
    }

    static boolean isDefined(String value) {
        return !Strings.isNullOrEmpty(value);
    }
}
