import { useEffect, useMemo, useState } from 'react';
import { Button, Empty, message, Spin, Tag } from 'antd';
import { ApiOutlined, KeyOutlined, SettingOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { ConnectorCredential, getCredentials } from '../api/ops';
import { getNodeTypes, NodeDefinition } from '../api/workflow';
import OpsLayout from '../components/OpsLayout';

const connectorCatalog = [
  { type: 'prometheus_query', credentialType: 'prometheus', title: 'Prometheus', description: '指标和 PromQL 即时查询', tone: 'green' },
  { type: 'loki_query', credentialType: 'loki', title: 'Loki', description: 'LogQL 日志检索', tone: 'blue' },
  { type: 'kubernetes_query', credentialType: 'kubernetes', title: 'Kubernetes', description: 'Pod、工作负载和 Event 只读查询', tone: 'cyan' },
  { type: 'database_health_check', credentialType: 'mysql', title: 'MySQL', description: '只读连接和健康验证', tone: 'gold' },
  { type: 'minio', credentialType: 'minio', title: 'MinIO / S3', description: '对象存储地址、Access Key 和 Bucket', tone: 'gold' },
  { type: 'alertmanager', credentialType: 'alertmanager', title: '告警系统', description: 'Alertmanager 地址、认证与告警 Webhook 接入', tone: 'purple' },
  { type: 'webhook_notify', credentialType: 'webhook', title: 'Webhook', description: '低风险通知与系统联动', tone: 'purple' },
];

const ConnectorsPage = () => {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [credentials, setCredentials] = useState<ConnectorCredential[]>([]);
  const [nodeTypes, setNodeTypes] = useState<NodeDefinition[]>([]);

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      try {
        const [credentialResult, nodeResult] = await Promise.all([getCredentials(), getNodeTypes()]);
        setCredentials(credentialResult.code === 200 ? credentialResult.data : []);
        setNodeTypes(nodeResult.code === 200 ? nodeResult.data : []);
      } catch (error) {
        message.error(error instanceof Error ? error.message : '连接器加载失败');
      } finally {
        setLoading(false);
      }
    };
    load();
  }, []);

  const enabledTypes = useMemo(
    () => new Set(nodeTypes.map((node) => node.nodeType)),
    [nodeTypes]
  );

  return (
    <OpsLayout
      eyebrow="Integrations"
      title="资源与连接器"
      description="监控、日志、集群、数据库和外部工具接入"
      actions={<Button icon={<SettingOutlined />} onClick={() => navigate('/mcp-tools')}>MCP 工具</Button>}
    >
      {loading ? (
        <div className="ops-page-loading"><Spin /></div>
      ) : (
        <>
          <section className="ops-connector-grid">
            {connectorCatalog.map((connector) => {
              const credentialCount = credentials.filter((item) =>
                connector.type.startsWith(item.connectorType)
                || connector.title.toLowerCase() === item.connectorType
              ).length;
              return (
                <article className="ops-connector-item" key={connector.type}>
                  <div className="ops-connector-head">
                    <span className={`ops-connector-icon is-${connector.tone}`}><ApiOutlined /></span>
                    <Tag color={enabledTypes.has(connector.type) ? 'success' : 'default'}>
                      {enabledTypes.has(connector.type) ? '可用' : '未注册'}
                    </Tag>
                  </div>
                  <h2>{connector.title}</h2>
                  <p>{connector.description}</p>
                  <div className="ops-connector-foot">
                    <span><KeyOutlined /> {credentialCount} 个凭证</span>
                    <Button
                      type="link"
                      onClick={() => navigate(`/credentials?type=${connector.credentialType}&create=1`)}
                    >
                      管理
                    </Button>
                  </div>
                </article>
              );
            })}
          </section>
          <section className="ops-data-panel" style={{ marginTop: 16, padding: 20 }}>
            <h2 style={{ marginTop: 0 }}>告警 Webhook 接入信息</h2>
            <p>
              在 Alertmanager 或其他告警平台中填写地址
              {' '}<code>{`${window.location.origin}/api/alerts/webhook`}</code>，
              请求头名称为 <code>X-PaiOps-Webhook-Token</code>。
              告警系统自身的地址和认证信息可点击上方“告警系统 → 管理”后加密保存。
            </p>
          </section>
          {connectorCatalog.length === 0 && <Empty description="暂无连接器" />}
        </>
      )}
    </OpsLayout>
  );
};

export default ConnectorsPage;
