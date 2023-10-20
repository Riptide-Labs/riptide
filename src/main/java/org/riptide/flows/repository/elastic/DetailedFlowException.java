package org.riptide.flows.repository.elastic;

import org.riptide.flows.pipeline.FlowException;

import java.util.List;

public abstract class DetailedFlowException extends FlowException {
    public DetailedFlowException(String message) {
        super(message);
    }

    public DetailedFlowException(String message, Throwable cause) {
        super(message, cause);
    }

    public abstract List<String> getDetailedLogMessages();
}
