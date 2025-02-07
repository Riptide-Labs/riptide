package org.riptide.flows.parser.exceptions;

public class MissingTemplateException extends Exception {

    public MissingTemplateException(final int templateId) {
        super(Integer.toString(templateId));
    }
}
