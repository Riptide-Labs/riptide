/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.exceptions;

public class IllegalFlowException extends Exception {
    public IllegalFlowException(final String message) {
        super(message);
    }
}
