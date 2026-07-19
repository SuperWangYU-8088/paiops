# ReAct Agent 分阶段实现

## 阶段一：节点内 ReAct Runtime

目标：先让工作流中可以拖入并执行 `react_agent` 节点，不改变现有 `llm`、`openai`、`deepseek` 等确定性 LLM 节点语义。

已落地边界：

- 新增 `react_agent` 节点类型，仍通过现有 `NodeExecutorFactory` 分发。
- 新增 `ReActAgentNodeExecutor`，在单个节点内部执行 `决策 -> 工具 -> 观察 -> 再决策` 循环。
- 新增 `AgentTool`、`AgentToolRegistry` 和首批内置工具：
  - `read_current_input`
  - `load_skill_detail`
  - `load_skill_reference`
- Agent 每轮只接受结构化 JSON 决策：
  - `tool_call`
  - `final_answer`
- 执行结果输出 `output`、`finalAnswer`、`toolTrace`、`steps`、`tokens`。
- 前端节点面板暴露 `ReAct Agent`，复用 LLM 配置面板，并增加 `maxSteps`。

阶段一限制：

- ReAct 循环由 `ReActAgentNodeExecutor` 内部控制，LangGraph 只看到一个普通节点。
- 工具调用轨迹会记录到节点输出中，但每一轮还不是独立的图节点。
- 暂不支持从 UI 精细选择工具，未配置时默认暴露全部已注册 Agent 工具。

## 阶段二：LangGraph 原生 Agent 状态机

当需要把每一轮 ReAct 都变成可视化、可恢复、可断点调试的图状态时，再升级到 LangGraph 条件边。

建议图结构：

```text
START -> agent_decide
agent_decide -- tool --> tool_execute
agent_decide -- final --> END
agent_decide -- max_steps --> END
tool_execute -> agent_decide
```

第二阶段应新增状态字段：

- `messages`
- `stepCount`
- `pendingToolCall`
- `observations`
- `toolTrace`
- `finalAnswer`
- `stopReason`

此时使用 LangGraph4J 的 `addConditionalEdges` 和 `AsyncEdgeAction` 做路由判断：

```java
graph.addConditionalEdges(
        "agent_decide",
        AsyncEdgeAction.edge_async(state -> {
            if (hasFinalAnswer(state)) {
                return "final";
            }
            if (reachMaxSteps(state)) {
                return "max_steps";
            }
            return "tool";
        }),
        Map.of(
                "tool", "tool_execute",
                "final", StateGraph.END,
                "max_steps", StateGraph.END
        )
);
```

原则：`EdgeAction` 只负责路由，不负责执行模型或工具。模型决策放在 `agent_decide` 节点，工具调用放在 `tool_execute` 节点。
