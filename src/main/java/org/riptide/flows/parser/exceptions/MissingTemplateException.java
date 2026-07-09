/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.exceptions;

public class MissingTemplateException extends Exception {

    public MissingTemplateException(final int templateId) {
        super(Integer.toString(templateId));
    }
}
