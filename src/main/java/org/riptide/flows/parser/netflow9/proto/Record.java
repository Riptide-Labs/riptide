/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.netflow9.proto;

// Named for the protocol, not the JDK: RFC 3954 and RFC 7011 both call this a Record, and
// nothing here refers to java.lang.Record. Renaming would cost the domain vocabulary
// across every parser to settle a clash that cannot actually arise.
@SuppressWarnings("AvoidCommonTypeNames")
public interface Record {
}
