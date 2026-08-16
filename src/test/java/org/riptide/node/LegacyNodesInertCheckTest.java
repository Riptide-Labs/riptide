/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import inet.ipaddr.IPAddressString;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyNodesInertCheckTest {

    private static NodeRegistry registryWith(final Map<String, NodeDefinition> nodes) {
        final NodeRegistry registry = new NodeRegistry();
        registry.setNodes(nodes);
        return registry;
    }

    private static NodeDefinition node() {
        final NodeDefinition definition = new NodeDefinition();
        definition.setSubnetAddress(new IPAddressString("10.0.0.0/24"));
        return definition;
    }

    private static long warnings(final Runnable check) {
        final var logger = (Logger) LoggerFactory.getLogger(LegacyNodesInertCheck.class);
        final var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            check.run();
            return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void apopulatedLegacyTreeWarnsThatItIsNoLongerRead() {
        final var check = new LegacyNodesInertCheck(registryWith(Map.of("core", node())));

        // a clean startup would otherwise read as "my configuration is live", and it is not
        assertThat(warnings(check::warnWhenLegacyNodesAreNoLongerRead)).isEqualTo(1);
    }

    @Test
    void anEmptyLegacyTreeSaysNothing() {
        final var check = new LegacyNodesInertCheck(registryWith(Map.of()));

        assertThat(warnings(check::warnWhenLegacyNodesAreNoLongerRead)).isZero();
    }
}
