import { Node } from '@xyflow/react';

const REACT_FLOW_NODE_TYPE = 'workflow';

const getFallbackLabel = (nodeType: string, nodeId: string) => {
  switch (nodeType) {
    case 'input':
      return '输入';
    case 'output':
      return '输出';
    case 'tts':
      return '语音合成';
    case 'llm':
      return '大模型';
    case 'react_agent':
      return 'ReAct Agent';
    case 'http_health_check':
      return 'HTTP 健康检查';
    case 'prometheus_query':
      return 'Prometheus 查询';
    case 'loki_query':
      return 'Loki 日志查询';
    case 'kubernetes_query':
      return 'Kubernetes 资源查询';
    case 'kubernetes_scale':
      return 'Kubernetes 扩缩容';
    case 'kubernetes_restart':
      return 'Kubernetes 滚动重启';
    case 'kubernetes_rollback':
      return 'Kubernetes 镜像回滚';
    case 'host_resource_check':
      return '本机资源检查';
    case 'database_health_check':
      return '数据库健康检查';
    case 'webhook_notify':
      return 'Webhook 通知';
    case 'change_gate':
      return '变更批准门禁';
    case 'manual_approval':
      return '人工审批';
    default:
      return nodeType || nodeId;
  }
};

export const getWorkflowNodeType = (node: Pick<Node, 'type' | 'data'>) => {
  const dataType = typeof node.data?.type === 'string' ? node.data.type : '';
  if (dataType) {
    return dataType;
  }

  return node.type && node.type !== REACT_FLOW_NODE_TYPE ? node.type : '';
};

export const normalizeWorkflowNode = (node: Node): Node => {
  const workflowNodeType = getWorkflowNodeType(node);

  return {
    ...node,
    type: REACT_FLOW_NODE_TYPE,
    data: {
      ...node.data,
      type: workflowNodeType,
      label: typeof node.data?.label === 'string' && node.data.label.trim()
        ? node.data.label
        : getFallbackLabel(workflowNodeType, node.id),
    },
  };
};

export const normalizeWorkflowNodes = (nodes: Node[]) => nodes.map(normalizeWorkflowNode);

export const serializeWorkflowNodes = (nodes: Node[]) =>
  nodes.map((node) => {
    // Runbook 只持久化配置引用，绝不把编辑器中的临时密钥写进工作流 JSON。
    const safeData = { ...(node.data || {}) };
    delete safeData.apiKey;
    delete safeData.token;
    delete safeData.password;
    delete safeData.secret;
    return {
      id: node.id,
      type: getWorkflowNodeType(node) || node.type,
      position: node.position,
      data: {
        ...safeData,
        type: getWorkflowNodeType(node) || node.type,
      },
    };
  });

export const createDefaultWorkflowNodes = (): Node[] => [
  {
    id: 'input-default',
    type: REACT_FLOW_NODE_TYPE,
    position: { x: 250, y: 100 },
    data: {
      label: '输入节点',
      type: 'input',
    },
  },
  {
    id: 'output-default',
    type: REACT_FLOW_NODE_TYPE,
    position: { x: 250, y: 400 },
    data: {
      label: '输出节点',
      type: 'output',
      outputParams: [],
      responseContent: '',
    },
  },
];
