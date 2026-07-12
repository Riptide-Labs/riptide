/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class NodesConfigMigrationCheckTest {

    @Test
    public void legacyIndexedKeysFailStartup() {
        final MockEnvironment environment = new MockEnvironment()
                .withProperty("riptide.nodes[0].subnet-address", "10.20.30.0/24");

        assertThatThrownBy(() -> new NodesConfigMigrationCheck(environment).failOnLegacyIndexedNodes())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.nodes.<name>");
    }

    @Test
    public void mapShapeKeysPass() {
        final MockEnvironment environment = new MockEnvironment()
                .withProperty("riptide.nodes.core-router.subnet-address", "10.20.30.0/24");

        assertThatCode(() -> new NodesConfigMigrationCheck(environment).failOnLegacyIndexedNodes())
                .doesNotThrowAnyException();
    }
}
