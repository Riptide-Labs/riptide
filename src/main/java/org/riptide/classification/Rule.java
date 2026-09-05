/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

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

    /**
     * Whether this rule carries an exporter filter &mdash; <b>not</b> whether one would be honoured.
     * Nothing matches on this field: {@code PreprocessedRule.of} derives the five value fields and
     * drops it, so {@code Classifier.of} builds no matcher for it. The field arrived with the
     * OpenNMS port and was never wired, and riptide has no filter engine to give it a meaning.
     *
     * <p>So this is a rejection predicate, not a matching one: {@code PreprocessedRule.of} calls it
     * and throws, which makes the rule one the engine names and skips rather than one that matches
     * every exporter instead of the one it names (#759). Anyone implementing the filter starts in
     * {@code PreprocessedRule.of} and {@code Classifier.of}, not at the importer &mdash; the request
     * already carries {@code exporterAddress} and {@code zone}, and neither is read by anything.</p>
     */
    default boolean hasExporterFilterDefinition() {
        return isDefined(getExporterFilter());
    }

    default boolean canBeReversed() {
        return isOmnidirectional()
                && (hasSrcPortDefinition() || hasSrcAddressDefinition() || hasDstPortDefinition() || hasDstAddressDefinition());
    }

    /**
     * Whether the rule sets any condition field at all. <b>Not</b> "would this rule match
     * anything": the exporter filter term counts a field no classifier evaluates. A rule setting it
     * is rejected in {@code PreprocessedRule.of} before it can classify (#759), so the term cannot
     * make a matching rule out of an unconstrained one on the engine's own path &mdash; but this
     * method is public API and nothing stops a caller invoking it on a rule that never goes near
     * the engine, where the term still overstates.
     *
     * <p>Has never had a caller: it arrived with the OpenNMS port in {@code 72eac745} and has been
     * uncalled since. Kept, not deleted, because it is the only written statement of what "an empty
     * rule" means &mdash; and flagged here so the next reader knows it is unexercised rather than
     * load-bearing.</p>
     */
    default boolean hasDefinition() {
        return hasProtocolDefinition()
                || hasDstAddressDefinition()
                || hasDstPortDefinition()
                || hasSrcAddressDefinition()
                || hasSrcPortDefinition()
                || hasExporterFilterDefinition();
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
