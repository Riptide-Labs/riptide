/*
 * Copyright 2025 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository;

import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/// NOOP implementation of a FlowRepository, otherwise spring context dies, which we for test purposes
/// don't want, but is in production the correct behavior.
@Service
@Primary
public class NoopRepository implements FlowRepository {
    @Override
    public void persist(List<EnrichedFlow> flows) throws FlowException, IOException {

    }
}
