/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp.collect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionDefinitionsTest {

    @Test
    void ifMibInterfacesHasFourteenValueColumnsAndThreeInfoColumns() {
        final var def = CollectionDefinitions.IF_MIB_INTERFACES;
        assertThat(def.columns().stream().filter(c -> c.type() != CollectionDefinition.ColumnType.INFO)).hasSize(14);
        assertThat(def.columns().stream().filter(c -> c.type() == CollectionDefinition.ColumnType.INFO))
                .extracting(CollectionDefinition.Column::metric).containsExactly("ifName", "ifAlias", "ifHighSpeed");
        assertThat(def.indexLabel()).isEqualTo("ifIndex");
        assertThat(def.columnOids()).hasSize(17);
    }
}
