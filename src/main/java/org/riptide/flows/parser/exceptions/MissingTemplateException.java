/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.exceptions;

public class MissingTemplateException extends Exception {

    public MissingTemplateException(final int templateId) {
        super(Integer.toString(templateId));
    }
}
