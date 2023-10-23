package org.riptide.repository.elastic;

import org.riptide.pipeline.FlowException;

public class UnrecoverableFlowException extends FlowException {
    public UnrecoverableFlowException(String message, Throwable cause) {
        super(message, cause);
    }
}
