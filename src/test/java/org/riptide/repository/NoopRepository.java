/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository;

import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/// NOOP implementation of a FlowRepository, otherwise spring context dies, which we for test purposes
/// don't want, but is in production the correct behavior.
/// Excluded under the e2e profile, where tests exercise the real ClickhouseRepository.
@Service
@Primary
@Profile("!e2e")
public class NoopRepository implements FlowRepository {
    @Override
    public void persist(List<EnrichedFlow> flows) throws FlowException, IOException {

    }
}
