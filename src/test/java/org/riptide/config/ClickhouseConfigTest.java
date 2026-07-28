/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The {@code asyncInserts} derivation: batching supersedes server-side coalescing, but only
 * while batching is actually enabled — with it off the pre-batching manage-mode default applies,
 * so a {@code batch.enabled=false} config does not silently land on the slowest combination.
 */
class ClickhouseConfigTest {

    @Test
    void asyncInsertsAreOffWhileBatchingIsEnabled() {
        final var config = new ClickhouseConfig();
        config.setManageSchema(true);

        // Batching is on by default and supersedes coalescing — even in manage mode.
        Assertions.assertThat(config.getBatch().isEnabled()).isTrue();
        Assertions.assertThat(config.isAsyncInserts()).isFalse();
    }

    @Test
    void asyncInsertsFallBackToManageModeWhenBatchingIsDisabled() {
        final var config = new ClickhouseConfig();
        config.getBatch().setEnabled(false);

        config.setManageSchema(true);
        Assertions.assertThat(config.isAsyncInserts()).isTrue();

        // Provisioned mode keeps the synchronous CHECK-barrier rejection.
        config.setManageSchema(false);
        Assertions.assertThat(config.isAsyncInserts()).isFalse();
    }

    @Test
    void explicitAsyncInsertsWinsOverEitherDerivation() {
        final var config = new ClickhouseConfig();

        // Explicitly on, against the batching-enabled derivation.
        config.setAsyncInserts(true);
        Assertions.assertThat(config.isAsyncInserts()).isTrue();

        // Explicitly off, against the batching-disabled manage-mode derivation.
        config.setAsyncInserts(false);
        config.getBatch().setEnabled(false);
        config.setManageSchema(true);
        Assertions.assertThat(config.isAsyncInserts()).isFalse();
    }
}
