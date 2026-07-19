import { useCallback, useEffect } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  Node,
  NodeProps,
  Connection,
  Handle,
  Position,
  addEdge,
  useNodesState,
  useEdgesState,
  OnNodesChange,
  OnEdgesChange,
  OnConnect,
  MarkerType,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useWorkflowStore } from '../store/workflowStore';

interface FlowCanvasProps {
  onNodeClick: (node: Node) => void;
}

type WorkflowNodeData = {
  label?: string;
  type?: string;
  provider?: string;
  model?: string;
  url?: string;
  endpoint?: string;
  query?: string;
  severity?: string;
  dryRun?: boolean;
  requiredPhrase?: string;
  skillName?: string;
  inputParams?: unknown[];
  outputParams?: unknown[];
  maxSteps?: number;
  agentStrategy?: string;
  tools?: string[];
  knowledgeBaseId?: string;
  knowledgeBaseName?: string;
  conditions?: Array<{ id: string; field?: string; operator?: string; value?: string }>;
};

type WorkflowCardNode = Node<WorkflowNodeData, 'workflow'>;

const WORKFLOW_EDGE_STYLE = {
  stroke: '#8b9bb4',
  strokeWidth: 2,
};

const WORKFLOW_EDGE_MARKER = {
  type: MarkerType.ArrowClosed,
  width: 20,
  height: 20,
};

const getDefaultNodeData = (type: string) => {
  switch (type) {
    case 'http_health_check':
      return {
        method: 'GET',
        timeoutSeconds: 10,
        expectedStatusMin: 200,
        expectedStatusMax: 399,
        includeBody: false,
        headers: '{}',
        outputParams: [
          { name: 'healthy', value: 'healthy' },
          { name: 'statusCode', value: 'statusCode' },
          { name: 'responseTimeMs', value: 'responseTimeMs' },
          { name: 'body', value: 'body' },
          { name: 'error', value: 'error' },
        ],
      };
    case 'prometheus_query':
      return {
        timeoutSeconds: 10,
        headers: '{}',
        outputParams: [
          { name: 'success', value: 'success' },
          { name: 'resultType', value: 'resultType' },
          { name: 'resultCount', value: 'resultCount' },
          { name: 'result', value: 'result' },
          { name: 'responseTimeMs', value: 'responseTimeMs' },
          { name: 'error', value: 'error' },
        ],
      };
    case 'loki_query':
      return {
        limit: 100,
        direction: 'backward',
        timeoutSeconds: 10,
        outputParams: [
          { name: 'success', value: 'success' },
          { name: 'resultType', value: 'resultType' },
          { name: 'streamCount', value: 'streamCount' },
          { name: 'result', value: 'result' },
          { name: 'responseTimeMs', value: 'responseTimeMs' },
          { name: 'error', value: 'error' },
        ],
      };
    case 'kubernetes_query':
      return {
        namespace: 'default',
        resource: 'pods',
        timeoutSeconds: 10,
        outputParams: [
          { name: 'success', value: 'success' },
          { name: 'count', value: 'count' },
          { name: 'items', value: 'items' },
          { name: 'resourceVersion', value: 'resourceVersion' },
          { name: 'error', value: 'error' },
        ],
      };
    case 'kubernetes_scale':
    case 'kubernetes_restart':
    case 'kubernetes_rollback':
      return {
        namespace: 'default',
        dryRun: true,
        timeoutSeconds: 20,
        outputParams: [
          { name: 'success', value: 'success' },
          { name: 'dryRun', value: 'dryRun' },
          { name: 'before', value: 'before' },
          { name: 'after', value: 'after' },
          { name: 'changeId', value: 'changeId' },
        ],
      };
    case 'host_resource_check':
      return {
        maxCpuPercent: 90,
        maxMemoryPercent: 90,
        outputParams: [
          { name: 'healthy', value: 'healthy' },
          { name: 'cpuUsagePercent', value: 'cpuUsagePercent' },
          { name: 'memoryUsagePercent', value: 'memoryUsagePercent' },
          { name: 'disks', value: 'disks' },
        ],
      };
    case 'database_health_check':
      return {
        timeoutSeconds: 10,
        outputParams: [
          { name: 'healthy', value: 'healthy' },
          { name: 'databaseProduct', value: 'databaseProduct' },
          { name: 'databaseVersion', value: 'databaseVersion' },
          { name: 'responseTimeMs', value: 'responseTimeMs' },
          { name: 'error', value: 'error' },
        ],
      };
    case 'webhook_notify':
      return {
        severity: 'info',
        dryRun: true,
        timeoutSeconds: 10,
        headers: '{}',
        outputParams: [
          { name: 'delivered', value: 'delivered' },
          { name: 'status', value: 'status' },
          { name: 'statusCode', value: 'statusCode' },
          { name: 'payload', value: 'payload' },
          { name: 'error', value: 'error' },
        ],
      };
    case 'change_gate':
      return {
        requiredPhrase: 'APPROVE',
        outputParams: [
          { name: 'changeGatePassed', value: 'changeGatePassed' },
          { name: 'changeGatePassedAt', value: 'changeGatePassedAt' },
          { name: 'gateNodeId', value: 'gateNodeId' },
        ],
      };
    case 'manual_approval':
      return {
        expiresMinutes: 60,
        outputParams: [
          { name: 'approved', value: 'approved' },
          { name: 'approvalId', value: 'approvalId' },
          { name: 'approvedBy', value: 'approvedBy' },
          { name: 'approvedAt', value: 'approvedAt' },
        ],
      };
    case 'image_generate':
      return {
        inputParams: [{ name: 'prompt', type: 'reference', value: '' }],
        outputParams: [{ name: 'image_url', value: 'imageUrl' }],
      };
    case 'video_generate':
      return {
        inputParams: [{ name: 'prompt', type: 'reference', value: '' }],
        outputParams: [{ name: 'video_url', value: 'videoUrl' }],
      };
    default:
      return {};
  }
};

const nodeMeta: Record<string, { icon: string; tone: string; caption: string }> = {
  input: { icon: 'IN', tone: 'node-tone-green', caption: 'Start input' },
  output: { icon: 'OUT', tone: 'node-tone-purple', caption: 'Final response' },
  llm: { icon: 'AI', tone: 'node-tone-blue', caption: 'Model call' },
  react_agent: { icon: 'AI', tone: 'node-tone-blue', caption: 'ReAct compatibility' },
  openai: { icon: 'AI', tone: 'node-tone-blue', caption: 'OpenAI' },
  deepseek: { icon: 'DS', tone: 'node-tone-blue', caption: 'DeepSeek' },
  qwen: { icon: 'QW', tone: 'node-tone-blue', caption: 'Qwen' },
  zhipu: { icon: 'ZP', tone: 'node-tone-blue', caption: 'ZhiPu' },
  ai_ping: { icon: 'AP', tone: 'node-tone-blue', caption: 'AIPing' },
  tts: { icon: 'TTS', tone: 'node-tone-amber', caption: 'Audio tool' },
  condition: { icon: 'IF', tone: 'node-tone-orange', caption: 'Condition branch' },
  http_health_check: { icon: 'HC', tone: 'node-tone-green', caption: 'HTTP health' },
  prometheus_query: { icon: 'PQ', tone: 'node-tone-green', caption: 'PromQL query' },
  loki_query: { icon: 'LQ', tone: 'node-tone-green', caption: 'LogQL query' },
  kubernetes_query: { icon: 'K8', tone: 'node-tone-green', caption: 'Kubernetes read only' },
  kubernetes_scale: { icon: 'KS', tone: 'node-tone-red', caption: 'High risk · approval' },
  kubernetes_restart: { icon: 'KR', tone: 'node-tone-red', caption: 'High risk · approval' },
  kubernetes_rollback: { icon: 'KB', tone: 'node-tone-red', caption: 'High risk · approval' },
  host_resource_check: { icon: 'HS', tone: 'node-tone-green', caption: 'Host resources' },
  database_health_check: { icon: 'DB', tone: 'node-tone-green', caption: 'Database health' },
  webhook_notify: { icon: 'WH', tone: 'node-tone-amber', caption: 'Dry run' },
  change_gate: { icon: 'CG', tone: 'node-tone-purple', caption: 'Approval gate' },
  manual_approval: { icon: 'AP', tone: 'node-tone-purple', caption: 'Manual approval' },
};

const getNodeMeta = (type?: string) => (
  nodeMeta[type || ''] || { icon: 'FN', tone: 'node-tone-slate', caption: 'Workflow node' }
);

const getToolLabels = (tools: string[]) => {
  const labels: string[] = [];
  if (tools.includes('web_search') || tools.includes('web_fetch')) {
    labels.push('联网搜索');
  }
  if (tools.includes('memory_write')) {
    labels.push('写入记忆');
  }
  return labels;
};

const getKnowledgeBaseLabel = (data: WorkflowNodeData) => {
  if (!data.knowledgeBaseId) {
    return '';
  }
  return data.knowledgeBaseName || '已启用';
};

const WorkflowNodeCard = ({ data, selected }: NodeProps<WorkflowCardNode>) => {
  const workflowType = data.type || '';
  const meta = getNodeMeta(workflowType);
  const inputCount = Array.isArray(data.inputParams) ? data.inputParams.length : 0;
  const outputCount = Array.isArray(data.outputParams) ? data.outputParams.length : 0;
  const modelLabel = data.url || data.endpoint || data.query || data.model || data.provider || data.skillName || meta.caption;
  const tools = Array.isArray(data.tools) ? data.tools : [];
  const toolLabels = getToolLabels(tools);
  const isReActMode = (workflowType === 'llm' && data.agentStrategy === 'react') || workflowType === 'react_agent';
  const knowledgeBaseLabel = getKnowledgeBaseLabel(data);

  const isCondition = workflowType === 'condition';
  const conditions = Array.isArray(data.conditions) ? data.conditions : [];
  const handleCount = isCondition ? conditions.length + 1 : 0;

  return (
    <div className={`workflow-node ${selected ? 'is-selected' : ''}`}>
      {workflowType !== 'input' && (
        <Handle
          type="target"
          position={Position.Top}
          className="workflow-node-handle"
        />
      )}
      <div className="workflow-node-header">
        <div className={`workflow-node-icon ${meta.tone}`}>{meta.icon}</div>
        <div className="workflow-node-title-wrap">
          <div className="workflow-node-title">{data.label || workflowType || '未命名节点'}</div>
          <div className="workflow-node-subtitle">{modelLabel}</div>
        </div>
      </div>
      {isCondition && conditions.length > 0 && (
        <div className="workflow-node-conditions">
          {conditions.map((c, i) => (
            <div key={c.id} className="workflow-node-condition-item">
              <span className="condition-badge">{i + 1}</span>
              <span className="condition-label">{c.field} {c.operator} {c.value}</span>
            </div>
          ))}
          <div className="workflow-node-condition-item condition-default">
            <span className="condition-badge default">D</span>
            <span className="condition-label">否则(默认)</span>
          </div>
        </div>
      )}
      {(isReActMode || knowledgeBaseLabel) && (
        <div className="workflow-node-conditions">
          {isReActMode && (
            <div className="workflow-node-condition-item">
              <span className="condition-badge">A</span>
              <span className="condition-label">Agent策略 ReAct</span>
            </div>
          )}
          {isReActMode && toolLabels.length > 0 && (
            <div className="workflow-node-condition-item">
              <span className="condition-badge default">{toolLabels.length}</span>
              <span className="condition-label">工具 {toolLabels.join(', ')}</span>
            </div>
          )}
          {knowledgeBaseLabel && (
            <div className="workflow-node-condition-item">
              <span className="condition-badge knowledge">K</span>
              <span className="condition-label">知识库 {knowledgeBaseLabel}</span>
            </div>
          )}
        </div>
      )}
      <div className="workflow-node-footer">
        <span>{workflowType || 'node'}</span>
        <span>{inputCount} in / {outputCount} out</span>
      </div>
      {workflowType === 'output' ? null : isCondition ? (
        <>
          {conditions.map((c, i) => (
            <Handle
              key={c.id}
              type="source"
              position={Position.Bottom}
              id={c.id}
              className="workflow-node-handle condition-handle"
              style={{ left: `${((i + 1) / (handleCount + 1)) * 100}%` }}
            />
          ))}
          <Handle
            type="source"
            position={Position.Bottom}
            id="default"
            className="workflow-node-handle condition-handle"
            style={{ left: `${(handleCount / (handleCount + 1)) * 100}%` }}
          />
        </>
      ) : (
        <Handle
          type="source"
          position={Position.Bottom}
          className="workflow-node-handle"
        />
      )}
    </div>
  );
};

const nodeTypes = {
  workflow: WorkflowNodeCard,
};

/**
 * 中间画布组件
 */
const FlowCanvas = ({ onNodeClick }: FlowCanvasProps) => {
  const { nodes: storeNodes, edges: storeEdges, setNodes: setStoreNodes, setEdges: setStoreEdges } = useWorkflowStore();
  
  const [nodes, setNodes, onNodesChange] = useNodesState(storeNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(storeEdges);

  // 当 store 中的 nodes/edges 变化时，同步更新到本地状态
  useEffect(() => {
    console.log('Store nodes changed:', storeNodes);
    setNodes(storeNodes);
  }, [storeNodes, setNodes]);

  useEffect(() => {
    console.log('Store edges changed:', storeEdges);
    const edgesWithMarkers = storeEdges.map(edge => ({
      ...edge,
      type: 'smoothstep',
      style: WORKFLOW_EDGE_STYLE,
      markerEnd: WORKFLOW_EDGE_MARKER,
    }));
    setEdges(edgesWithMarkers);
  }, [storeEdges, setEdges]);

  // 同步到 store
  const handleNodesChange: OnNodesChange = useCallback((changes) => {
    onNodesChange(changes);
    // 使用 setTimeout 确保状态更新后再同步
    setTimeout(() => {
      setNodes((currentNodes) => {
        setStoreNodes(currentNodes);
        return currentNodes;
      });
    }, 0);
  }, [onNodesChange, setNodes, setStoreNodes]);

  const handleEdgesChange: OnEdgesChange = useCallback((changes) => {
    onEdgesChange(changes);
    setTimeout(() => {
      setEdges((currentEdges) => {
        setStoreEdges(currentEdges);
        return currentEdges;
      });
    }, 0);
  }, [onEdgesChange, setEdges, setStoreEdges]);

  const handleConnect: OnConnect = useCallback((connection: Connection) => {
    console.log('Connection created:', connection);
    setEdges((eds) => {
      const newEdge = {
        ...connection,
        type: 'smoothstep',
        style: WORKFLOW_EDGE_STYLE,
        markerEnd: WORKFLOW_EDGE_MARKER,
      };
      console.log('New edge:', newEdge);
      const updatedEdges = addEdge(newEdge, eds);
      setStoreEdges(updatedEdges);
      return updatedEdges;
    });
  }, [setEdges, setStoreEdges]);

  // 处理拖拽放置
  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();

      const type = event.dataTransfer.getData('application/reactflow-type');
      const label = event.dataTransfer.getData('application/reactflow-label');

      if (!type) return;

      const reactFlowBounds = (event.target as HTMLElement).getBoundingClientRect();
      const position = {
        x: event.clientX - reactFlowBounds.left - 75,
        y: event.clientY - reactFlowBounds.top - 25,
      };

      const newNode: Node = {
        id: `${type}-${Date.now()}`,
        type: 'workflow',
        position,
        data: { label: label || type, type, ...getDefaultNodeData(type) },
      };

      setNodes((nds) => {
        const updatedNodes = nds.concat(newNode);
        setStoreNodes(updatedNodes);
        return updatedNodes;
      });
    },
    [setNodes, setStoreNodes]
  );

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const handleNodeClick = useCallback(
    (_: React.MouseEvent, node: Node) => {
      onNodeClick(node);
    },
    [onNodeClick]
  );

  return (
    <div className="h-full w-full">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={handleNodesChange}
        onEdgesChange={handleEdgesChange}
        onConnect={handleConnect}
        onDrop={onDrop}
        onDragOver={onDragOver}
        onNodeClick={handleNodeClick}
        deleteKeyCode={null}
        nodeTypes={nodeTypes}
        defaultEdgeOptions={{
          type: 'smoothstep',
          style: WORKFLOW_EDGE_STYLE,
          markerEnd: WORKFLOW_EDGE_MARKER,
        }}
        fitView
        fitViewOptions={{ padding: 0.28 }}
        snapToGrid
        snapGrid={[16, 16]}
      >
        <Background color="#d8dee9" gap={18} size={1.2} />
        <Controls className="workflow-controls" />
        <MiniMap
          pannable
          zoomable
          className="workflow-minimap"
          nodeColor={(node) => getNodeMeta(String(node.data?.type || '')).tone.includes('green') ? '#22c55e' : '#64748b'}
        />
      </ReactFlow>
    </div>
  );
};

export default FlowCanvas;
