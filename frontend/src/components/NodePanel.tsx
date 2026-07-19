import { useEffect, useState } from 'react';
import { Collapse, Tag, message } from 'antd';
import { getNodeTypes, NodeDefinition } from '../api/workflow';

interface NodePanelProps {
  onDragStart: (event: React.DragEvent, nodeType: string, displayName: string) => void;
}

/**
 * 左侧节点面板组件
 */
const NodePanel = ({ onDragStart }: NodePanelProps) => {
  const [nodeTypes, setNodeTypes] = useState<NodeDefinition[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    loadNodeTypes();
  }, []);

  const loadNodeTypes = async () => {
    setLoading(true);
    try {
      const result = await getNodeTypes();
      console.log('Node types API result:', result);
      if (result.code === 200) {
        setNodeTypes(result.data);
      } else {
        console.error('Failed to load node types:', result);
        message.error(`加载节点类型失败: ${result.message || '未知错误'}`);
      }
    } catch (error) {
      console.error('Error loading node types:', error);
      message.error(`加载节点类型失败: ${error instanceof Error ? error.message : '网络错误'}`);
    } finally {
      setLoading(false);
    }
  };

  // 运维节点按职责分组，旧节点类型仍由后端保留兼容。
  const llmNodes = nodeTypes.filter((node) => node.category === 'LLM');
  const observabilityNodes = nodeTypes.filter((node) => node.category === 'OBSERVABILITY');
  const actionNodes = nodeTypes.filter((node) => ['ACTION', 'TOOL'].includes(node.category));
  const governanceNodes = nodeTypes.filter((node) => ['GOVERNANCE', 'CONTROL'].includes(node.category));

  const getNodeTone = (node: NodeDefinition) => {
    if (node.nodeType === 'input') return 'node-library-green';
    if (node.nodeType === 'output') return 'node-library-purple';
    if (node.category === 'OBSERVABILITY') return 'node-library-green';
    if (['ACTION', 'TOOL'].includes(node.category)) return 'node-library-amber';
    if (['GOVERNANCE', 'CONTROL'].includes(node.category)) return 'node-library-purple';
    return 'node-library-blue';
  };

  const renderNodeItem = (node: NodeDefinition) => (
    <div
      key={node.nodeType}
      draggable
      onDragStart={(e) => onDragStart(e, node.nodeType, node.displayName)}
      className="node-library-item"
    >
      <div className={`node-library-icon ${getNodeTone(node)}`}>{node.icon}</div>
      <div className="min-w-0 flex-1">
        <div className="node-library-title">{node.displayName}</div>
        <div className="node-library-meta">
          <span>{node.nodeType}</span>
          {node.riskLevel && (
            <span className={`node-risk-tag is-${node.riskLevel.toLowerCase()}`}>
              {node.riskLevel === 'READ_ONLY'
                ? '只读'
                : node.riskLevel === 'LOW_RISK'
                  ? '低风险'
                  : node.riskLevel === 'GOVERNANCE'
                    ? '治理'
                    : '高风险'}
            </span>
          )}
        </div>
      </div>
      <span className="node-library-drag" aria-hidden="true">⋮⋮</span>
    </div>
  );

  const items = [
    {
      key: 'observability',
      label: (
        <div className="node-library-section-title">
          <span>观测与诊断</span>
          <Tag color="green">{observabilityNodes.length}</Tag>
        </div>
      ),
      children: (
        <div className="space-y-2">
          {observabilityNodes.length > 0 ? (
            observabilityNodes.map(renderNodeItem)
          ) : (
            <div className="text-gray-400 text-center py-4">暂无节点</div>
          )}
        </div>
      ),
    },
    {
      key: 'llm',
      label: (
        <div className="node-library-section-title">
          <span>智能诊断</span>
          <Tag color="blue">{llmNodes.length}</Tag>
        </div>
      ),
      children: (
        <div className="space-y-2">
          {llmNodes.length > 0 ? (
            llmNodes.map(renderNodeItem)
          ) : (
            <div className="text-gray-400 text-center py-4">暂无节点</div>
          )}
        </div>
      ),
    },
    {
      key: 'action',
      label: (
        <div className="node-library-section-title">
          <span>通知与动作</span>
          <Tag color="gold">{actionNodes.length}</Tag>
        </div>
      ),
      children: (
        <div className="space-y-2">
          {actionNodes.length > 0 ? (
            actionNodes.map(renderNodeItem)
          ) : (
            <div className="text-gray-400 text-center py-4">暂无节点</div>
          )}
        </div>
      ),
    },
    ...(governanceNodes.length > 0
      ? [
          {
            key: 'governance',
            label: (
              <div className="node-library-section-title">
                <span>流程治理</span>
                <Tag color="purple">{governanceNodes.length}</Tag>
              </div>
            ),
            children: (
              <div className="space-y-2">
                {governanceNodes.map(renderNodeItem)}
              </div>
            ),
          },
        ]
      : []),
  ];

  return (
    <div className="h-full flex flex-col overflow-hidden">
      <div className="node-library-header">
        <div>
          <h3 className="node-library-heading">Runbook 节点</h3>
          <p className="node-library-desc">观测、诊断、治理与动作</p>
        </div>
      </div>
      <div className="node-library-body">
        {loading ? (
          <div className="text-center py-8 text-gray-400">加载中...</div>
        ) : (
          <>
            <Collapse
              defaultActiveKey={['observability', 'llm', 'action', 'governance']}
              ghost
              items={items}
              bordered={false}
            />
            <div className="node-library-tip">默认只开放低风险运维能力</div>
          </>
        )}
      </div>
    </div>
  );
};

export default NodePanel;
