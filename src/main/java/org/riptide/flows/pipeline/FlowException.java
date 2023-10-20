package org.riptide.flows.pipeline;

public class FlowException extends Exception {

    public FlowException(final String message) {
        super(message);
    }

    public FlowException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public FlowException(final Throwable cause) {
        super(cause);
    }
}
