package org.riptide.flows.repository.elastic;

import org.riptide.flows.pipeline.FlowException;

public class UnrecoverableFlowException extends FlowException {
    public UnrecoverableFlowException(String message, Throwable cause) {
        super(message, cause);
    }
}
