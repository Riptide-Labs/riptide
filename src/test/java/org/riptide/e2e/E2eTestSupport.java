/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import org.assertj.core.api.Assertions;

import java.net.DatagramSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/** Shared helpers for the e2e test tier. */
final class E2eTestSupport {

    private E2eTestSupport() {
    }

    /** Deadline-based polling; fails the test on timeout. Never a bare sleep. */
    static void await(final Duration timeout, final String description, final BooleanSupplier condition)
            throws InterruptedException {
        final var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(2000);
        }
        Assertions.fail("Timed out after %s waiting for %s".formatted(timeout, description));
    }

    static int freeUdpPort() {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (final Exception e) {
            throw new IllegalStateException("No free UDP port available", e);
        }
    }
}
