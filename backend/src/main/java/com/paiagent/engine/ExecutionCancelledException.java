package com.paiagent.engine;

public class ExecutionCancelledException extends RuntimeException {

    public ExecutionCancelledException() {
        super("执行任务已取消");
    }
}
