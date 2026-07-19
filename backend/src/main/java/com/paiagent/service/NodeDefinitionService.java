package com.paiagent.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paiagent.entity.NodeDefinition;
import com.paiagent.engine.safety.NodeSafetyPolicy;
import com.paiagent.mapper.NodeDefinitionMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点定义服务
 */
@Service
public class NodeDefinitionService extends ServiceImpl<NodeDefinitionMapper, NodeDefinition> {
    private final NodeSafetyPolicy nodeSafetyPolicy;

    public NodeDefinitionService(NodeSafetyPolicy nodeSafetyPolicy) {
        this.nodeSafetyPolicy = nodeSafetyPolicy;
    }

    private static final List<String> HIDDEN_STANDALONE_AGENT_NODE_TYPES = List.of(
            "react_agent",
            "web_search",
            "web_fetch",
            "vision_analyze",
            "memory_write",
            "memory_retrieve",
            "knowledge_upsert",
            "knowledge_retrieve"
    );
    private static final List<String> HIDDEN_NON_OPS_NODE_TYPES = List.of(
            "tts",
            "image_generate",
            "video_generate"
    );
    
    /**
     * 查询所有节点定义
     */
    public List<NodeDefinition> listAllNodeDefinitions() {
        Map<String, NodeDefinition> nodeDefinitionMap = new LinkedHashMap<>();
        this.list().forEach(node -> nodeDefinitionMap.put(node.getNodeType(), node));

        nodeDefinitionMap.putIfAbsent("llm", createGenericLlmNodeDefinition());
        addOpsNodeDefinitions(nodeDefinitionMap);
        nodeDefinitionMap.putIfAbsent("memory_write", createNodeDefinition(
                "memory_write", "写入记忆", "MEMORY", "MW",
                "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"memoryId\":{\"type\":\"string\"},\"scope\":{\"type\":\"string\"},\"stored\":{\"type\":\"boolean\"}}}",
                "{\"type\":\"object\",\"properties\":{\"configId\":{\"type\":\"number\"},\"content\":{\"type\":\"string\"},\"memoryType\":{\"type\":\"string\",\"default\":\"fact\"},\"tags\":{\"type\":\"string\"},\"scope\":{\"type\":\"string\",\"default\":\"workflow\"}}}"
        ));
        nodeDefinitionMap.putIfAbsent("memory_retrieve", createNodeDefinition(
                "memory_retrieve", "召回记忆", "MEMORY", "MR",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"memories\":{\"type\":\"array\"},\"context\":{\"type\":\"string\"},\"citations\":{\"type\":\"array\"}}}",
                "{\"type\":\"object\",\"properties\":{\"configId\":{\"type\":\"number\"},\"query\":{\"type\":\"string\"},\"scope\":{\"type\":\"string\",\"default\":\"workflow\"},\"topK\":{\"type\":\"number\",\"default\":5},\"tags\":{\"type\":\"string\"}}}"
        ));
        nodeDefinitionMap.putIfAbsent("knowledge_upsert", createNodeDefinition(
                "knowledge_upsert", "写入知识库", "KNOWLEDGE", "KU",
                "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"knowledgeBaseId\":{\"type\":\"string\"},\"contentId\":{\"type\":\"string\"},\"chunkCount\":{\"type\":\"number\"},\"indexed\":{\"type\":\"boolean\"}}}",
                "{\"type\":\"object\",\"properties\":{\"configId\":{\"type\":\"number\"},\"knowledgeBaseId\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"sourceUrl\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"tags\":{\"type\":\"string\"}}}"
        ));
        nodeDefinitionMap.putIfAbsent("knowledge_retrieve", createNodeDefinition(
                "knowledge_retrieve", "检索知识库", "KNOWLEDGE", "KR",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"chunks\":{\"type\":\"array\"},\"citations\":{\"type\":\"array\"},\"context\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"configId\":{\"type\":\"number\"},\"knowledgeBaseId\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"},\"topK\":{\"type\":\"number\",\"default\":5},\"scoreThreshold\":{\"type\":\"number\",\"default\":0.2}}}"
        ));
        nodeDefinitionMap.putIfAbsent("image_generate", createNodeDefinition(
                "image_generate", "图片生成", "TOOL", "IMG",
                "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"imageUrl\":{\"type\":\"string\"},\"imageUrls\":{\"type\":\"array\"},\"prompt\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"},\"metadata\":{\"type\":\"object\"}}}",
                "{\"type\":\"object\",\"properties\":{\"configId\":{\"type\":\"number\"},\"prompt\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"},\"referenceImageUrl\":{\"type\":\"string\"},\"size\":{\"type\":\"string\",\"default\":\"1024x1024\"},\"style\":{\"type\":\"string\"},\"count\":{\"type\":\"number\",\"default\":1},\"negativePrompt\":{\"type\":\"string\"},\"steps\":{\"type\":\"number\"},\"cfgScale\":{\"type\":\"number\"},\"seed\":{\"type\":\"number\"},\"textMode\":{\"type\":\"boolean\",\"default\":true}}}"
        ));
        nodeDefinitionMap.putIfAbsent("video_generate", createNodeDefinition(
                "video_generate", "视频生成", "TOOL", "VID",
                "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"},\"videoUrl\":{\"type\":\"string\"},\"coverUrl\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"},\"metadata\":{\"type\":\"object\"}}}",
                "{\"type\":\"object\",\"properties\":{\"configId\":{\"type\":\"number\"},\"prompt\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"},\"referenceImageUrl\":{\"type\":\"string\"},\"duration\":{\"type\":\"number\",\"default\":5},\"resolution\":{\"type\":\"string\"},\"ratio\":{\"type\":\"string\",\"default\":\"adaptive\"},\"cameraMotion\":{\"type\":\"string\"}}}"
        ));
        nodeDefinitionMap.values().forEach(node ->
                node.setRiskLevel(nodeSafetyPolicy.riskLevel(node.getNodeType()).name()));
        return nodeDefinitionMap.values().stream()
                .filter(node -> !HIDDEN_STANDALONE_AGENT_NODE_TYPES.contains(node.getNodeType()))
                .filter(node -> !HIDDEN_NON_OPS_NODE_TYPES.contains(node.getNodeType()))
                .filter(node -> !"LLM".equals(node.getCategory())
                        || "llm".equals(node.getNodeType()))
                .toList();
    }
    
    /**
     * 根据节点类型查询
     */
    public NodeDefinition getByNodeType(String nodeType) {
        if ("llm".equals(nodeType)) {
            return createGenericLlmNodeDefinition();
        }
        if ("react_agent".equals(nodeType)) {
            return createReActAgentNodeDefinition();
        }
        Map<String, NodeDefinition> opsDefinitions = new LinkedHashMap<>();
        addOpsNodeDefinitions(opsDefinitions);
        if (opsDefinitions.containsKey(nodeType)) {
            NodeDefinition definition = opsDefinitions.get(nodeType);
            definition.setRiskLevel(nodeSafetyPolicy.riskLevel(nodeType).name());
            return definition;
        }

        return this.lambdaQuery()
                .eq(NodeDefinition::getNodeType, nodeType)
                .one();
    }

    private NodeDefinition createGenericLlmNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("llm");
        nodeDefinition.setDisplayName("大模型");
        nodeDefinition.setCategory("LLM");
        nodeDefinition.setIcon("AI");
        nodeDefinition.setInputSchema("{\"type\": \"object\", \"properties\": {\"input\": {\"type\": \"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\": \"object\", \"properties\": {\"output\": {\"type\": \"string\"}, \"tokens\": {\"type\": \"number\"}}}");
        nodeDefinition.setConfigSchema("{\"type\": \"object\", \"properties\": {\"provider\": {\"type\": \"string\"}, \"configId\": {\"type\": \"number\"}, \"model\": {\"type\": \"string\"}, \"prompt\": {\"type\": \"string\"}, \"temperature\": {\"type\": \"number\", \"default\": 0.7}, \"maxTokens\": {\"type\": \"number\", \"default\": 1000}, \"agentStrategy\": {\"type\": \"string\", \"default\": \"none\"}, \"maxSteps\": {\"type\": \"number\", \"default\": 5}, \"tools\": {\"type\": \"array\"}, \"memoryEnabled\": {\"type\": \"boolean\", \"default\": false}, \"memoryTopK\": {\"type\": \"number\", \"default\": 5}, \"knowledgeBaseId\": {\"type\": \"string\"}, \"knowledgeTopK\": {\"type\": \"number\", \"default\": 5}, \"knowledgeScoreThreshold\": {\"type\": \"number\", \"default\": 0.2}}}");
        return nodeDefinition;
    }

    private void addOpsNodeDefinitions(Map<String, NodeDefinition> definitions) {
        definitions.putIfAbsent("http_health_check", createNodeDefinition(
                "http_health_check", "HTTP 健康检查", "OBSERVABILITY", "HC",
                "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"healthy\":{\"type\":\"boolean\"},\"statusCode\":{\"type\":\"number\"},\"responseTimeMs\":{\"type\":\"number\"},\"body\":{\"type\":\"string\"},\"error\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},\"credentialId\":{\"type\":\"number\"},\"method\":{\"type\":\"string\",\"default\":\"GET\"},\"timeoutSeconds\":{\"type\":\"number\",\"default\":10},\"expectedStatusMin\":{\"type\":\"number\",\"default\":200},\"expectedStatusMax\":{\"type\":\"number\",\"default\":399},\"includeBody\":{\"type\":\"boolean\",\"default\":false},\"headers\":{\"type\":\"string\"}}}"
        ));
        definitions.putIfAbsent("prometheus_query", createNodeDefinition(
                "prometheus_query", "Prometheus 查询", "OBSERVABILITY", "PQ",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"resultType\":{\"type\":\"string\"},\"resultCount\":{\"type\":\"number\"},\"result\":{\"type\":\"array\"},\"responseTimeMs\":{\"type\":\"number\"},\"error\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"endpoint\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"},\"credentialId\":{\"type\":\"number\"},\"timeoutSeconds\":{\"type\":\"number\",\"default\":10},\"headers\":{\"type\":\"string\"}}}"
        ));
        definitions.putIfAbsent("loki_query", createNodeDefinition(
                "loki_query", "Loki 日志查询", "OBSERVABILITY", "LQ",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"resultType\":{\"type\":\"string\"},\"streamCount\":{\"type\":\"number\"},\"result\":{\"type\":\"array\"},\"responseTimeMs\":{\"type\":\"number\"},\"error\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"endpoint\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"},\"credentialId\":{\"type\":\"number\"},\"limit\":{\"type\":\"number\",\"default\":100},\"direction\":{\"type\":\"string\",\"default\":\"backward\"},\"timeoutSeconds\":{\"type\":\"number\",\"default\":10}}}"
        ));
        definitions.putIfAbsent("kubernetes_query", createNodeDefinition(
                "kubernetes_query", "Kubernetes 资源查询", "OBSERVABILITY", "K8",
                "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"count\":{\"type\":\"number\"},\"items\":{\"type\":\"array\"},\"resourceVersion\":{\"type\":\"string\"},\"error\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"endpoint\":{\"type\":\"string\"},\"credentialId\":{\"type\":\"number\"},\"namespace\":{\"type\":\"string\",\"default\":\"default\"},\"resource\":{\"type\":\"string\",\"default\":\"pods\"},\"resourceName\":{\"type\":\"string\"},\"labelSelector\":{\"type\":\"string\"},\"timeoutSeconds\":{\"type\":\"number\",\"default\":10}}}"
        ));
        definitions.putIfAbsent("kubernetes_scale", createNodeDefinition(
                "kubernetes_scale", "Kubernetes 扩缩容", "ACTION", "KS",
                "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"dryRun\":{\"type\":\"boolean\"},\"before\":{\"type\":\"object\"},\"after\":{\"type\":\"object\"}}}",
                "{\"type\":\"object\",\"required\":[\"credentialId\",\"deployment\",\"replicas\"],\"properties\":{\"endpoint\":{\"type\":\"string\"},\"credentialId\":{\"type\":\"number\"},\"namespace\":{\"type\":\"string\",\"default\":\"default\"},\"deployment\":{\"type\":\"string\"},\"replicas\":{\"type\":\"number\",\"minimum\":0,\"maximum\":200},\"dryRun\":{\"type\":\"boolean\",\"default\":true},\"timeoutSeconds\":{\"type\":\"number\",\"default\":20}}}"
        ));
        definitions.putIfAbsent("kubernetes_restart", createNodeDefinition(
                "kubernetes_restart", "Kubernetes 滚动重启", "ACTION", "KR",
                "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"dryRun\":{\"type\":\"boolean\"},\"changeId\":{\"type\":\"string\"},\"before\":{\"type\":\"object\"},\"after\":{\"type\":\"object\"}}}",
                "{\"type\":\"object\",\"required\":[\"credentialId\",\"deployment\"],\"properties\":{\"endpoint\":{\"type\":\"string\"},\"credentialId\":{\"type\":\"number\"},\"namespace\":{\"type\":\"string\",\"default\":\"default\"},\"deployment\":{\"type\":\"string\"},\"changeId\":{\"type\":\"string\"},\"dryRun\":{\"type\":\"boolean\",\"default\":true},\"timeoutSeconds\":{\"type\":\"number\",\"default\":20}}}"
        ));
        definitions.putIfAbsent("kubernetes_rollback", createNodeDefinition(
                "kubernetes_rollback", "Kubernetes 镜像回滚", "ACTION", "KB",
                "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"dryRun\":{\"type\":\"boolean\"},\"before\":{\"type\":\"object\"},\"after\":{\"type\":\"object\"}}}",
                "{\"type\":\"object\",\"required\":[\"credentialId\",\"deployment\",\"container\",\"targetImage\"],\"properties\":{\"endpoint\":{\"type\":\"string\"},\"credentialId\":{\"type\":\"number\"},\"namespace\":{\"type\":\"string\",\"default\":\"default\"},\"deployment\":{\"type\":\"string\"},\"container\":{\"type\":\"string\"},\"targetImage\":{\"type\":\"string\"},\"dryRun\":{\"type\":\"boolean\",\"default\":true},\"timeoutSeconds\":{\"type\":\"number\",\"default\":20}}}"
        ));
        definitions.putIfAbsent("host_resource_check", createNodeDefinition(
                "host_resource_check", "本机资源检查", "OBSERVABILITY", "HS",
                "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\",\"properties\":{\"healthy\":{\"type\":\"boolean\"},\"cpuUsagePercent\":{\"type\":\"number\"},\"memoryUsagePercent\":{\"type\":\"number\"},\"disks\":{\"type\":\"array\"}}}",
                "{\"type\":\"object\",\"properties\":{\"maxCpuPercent\":{\"type\":\"number\",\"default\":90},\"maxMemoryPercent\":{\"type\":\"number\",\"default\":90}}}"
        ));
        definitions.putIfAbsent("database_health_check", createNodeDefinition(
                "database_health_check", "数据库健康检查", "OBSERVABILITY", "DB",
                "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\",\"properties\":{\"healthy\":{\"type\":\"boolean\"},\"databaseProduct\":{\"type\":\"string\"},\"databaseVersion\":{\"type\":\"string\"},\"responseTimeMs\":{\"type\":\"number\"},\"error\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"credentialId\":{\"type\":\"number\"},\"timeoutSeconds\":{\"type\":\"number\",\"default\":10}}}"
        ));
        definitions.putIfAbsent("webhook_notify", createNodeDefinition(
                "webhook_notify", "Webhook 通知", "ACTION", "WH",
                "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"delivered\":{\"type\":\"boolean\"},\"status\":{\"type\":\"string\"},\"statusCode\":{\"type\":\"number\"},\"payload\":{\"type\":\"object\"},\"error\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},\"credentialId\":{\"type\":\"number\"},\"message\":{\"type\":\"string\"},\"severity\":{\"type\":\"string\",\"default\":\"info\"},\"dryRun\":{\"type\":\"boolean\",\"default\":true},\"includeContext\":{\"type\":\"boolean\",\"default\":false},\"timeoutSeconds\":{\"type\":\"number\",\"default\":10},\"headers\":{\"type\":\"string\"}}}"
        ));
        definitions.putIfAbsent("change_gate", createNodeDefinition(
                "change_gate", "变更批准门禁", "GOVERNANCE", "CG",
                "{\"type\":\"object\",\"properties\":{\"approvalToken\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"changeGatePassed\":{\"type\":\"boolean\"},\"changeGatePassedAt\":{\"type\":\"string\"},\"gateNodeId\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"requiredPhrase\":{\"type\":\"string\",\"default\":\"APPROVE\"}}}"
        ));
        definitions.putIfAbsent("manual_approval", createNodeDefinition(
                "manual_approval", "人工审批", "GOVERNANCE", "AP",
                "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\",\"properties\":{\"approved\":{\"type\":\"boolean\"},\"approvalId\":{\"type\":\"number\"},\"approvedBy\":{\"type\":\"string\"},\"approvedAt\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"approvalReason\":{\"type\":\"string\"},\"expiresMinutes\":{\"type\":\"number\",\"default\":60}}}"
        ));
    }

    private NodeDefinition createReActAgentNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("react_agent");
        nodeDefinition.setDisplayName("ReAct Agent");
        nodeDefinition.setCategory("LLM");
        nodeDefinition.setIcon("RA");
        nodeDefinition.setInputSchema("{\"type\": \"object\", \"properties\": {\"input\": {\"type\": \"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\": \"object\", \"properties\": {\"output\": {\"type\": \"string\"}, \"finalAnswer\": {\"type\": \"string\"}, \"toolTrace\": {\"type\": \"array\"}, \"steps\": {\"type\": \"number\"}, \"tokens\": {\"type\": \"number\"}}}");
        nodeDefinition.setConfigSchema("{\"type\": \"object\", \"properties\": {\"provider\": {\"type\": \"string\"}, \"configId\": {\"type\": \"number\"}, \"model\": {\"type\": \"string\"}, \"prompt\": {\"type\": \"string\"}, \"temperature\": {\"type\": \"number\", \"default\": 0.7}, \"maxSteps\": {\"type\": \"number\", \"default\": 5}, \"tools\": {\"type\": \"array\"}}}");
        return nodeDefinition;
    }

    private NodeDefinition createNodeDefinition(String nodeType, String displayName, String category,
                                                String icon, String inputSchema, String outputSchema,
                                                String configSchema) {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType(nodeType);
        nodeDefinition.setDisplayName(displayName);
        nodeDefinition.setCategory(category);
        nodeDefinition.setIcon(icon);
        nodeDefinition.setInputSchema(inputSchema);
        nodeDefinition.setOutputSchema(outputSchema);
        nodeDefinition.setConfigSchema(configSchema);
        return nodeDefinition;
    }
}
