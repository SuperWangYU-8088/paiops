package com.paiagent.engine;

public class WorkflowPausedException extends RuntimeException {

    private final String nodeId;

    public WorkflowPausedException(String nodeId, String message) {
        super(message);
        this.nodeId = nodeId;
    }

    public String getNodeId() {
        return nodeId;
    }
}
