import { useEffect, useState } from 'react';
import { Button, Empty, Form, Input, message, Modal, Popconfirm, Select, Spin, Tag } from 'antd';
import { DeleteOutlined, KeyOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import {
  ConnectorCredential,
  ConnectorCredentialInput,
  createCredential,
  deleteCredential,
  getCredentials,
  updateCredential,
} from '../api/ops';
import OpsLayout from '../components/OpsLayout';
import { formatDateTime } from '../utils/format';

type CredentialFormValue = {
  name: string;
  connectorType: string;
  description?: string;
  endpoint?: string;
  bearerToken?: string;
  kubeconfig?: string;
  caCert?: string;
  namespace?: string;
  jdbcUrl?: string;
  username?: string;
  password?: string;
  authorization?: string;
  tenantId?: string;
  accessKey?: string;
  secretKey?: string;
  bucket?: string;
  useSsl?: string;
};

const connectorTypes = [
  { value: 'prometheus', label: 'Prometheus' },
  { value: 'loki', label: 'Loki' },
  { value: 'kubernetes', label: 'Kubernetes' },
  { value: 'mysql', label: 'MySQL' },
  { value: 'minio', label: 'MinIO / S3' },
  { value: 'alertmanager', label: 'Alertmanager / 告警系统' },
  { value: 'webhook', label: 'Webhook' },
];

const CredentialsPage = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const [form] = Form.useForm<CredentialFormValue>();
  const connectorType = Form.useWatch('connectorType', form);
  const [credentials, setCredentials] = useState<ConnectorCredential[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<number>();
  const [editing, setEditing] = useState<ConnectorCredential | null>();
  const [modalOpen, setModalOpen] = useState(false);
  const requestedConnectorType = searchParams.get('type');
  const shouldOpenCreate = searchParams.get('create') === '1';

  const load = async () => {
    setLoading(true);
    try {
      const result = await getCredentials();
      setCredentials(result.code === 200 ? result.data : []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '凭证加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldValue('connectorType', 'prometheus');
    setModalOpen(true);
  };

  useEffect(() => {
    if (!shouldOpenCreate) return;

    const initialType = connectorTypes.some((option) => option.value === requestedConnectorType)
      ? requestedConnectorType!
      : 'prometheus';
    setEditing(null);
    form.resetFields();
    form.setFieldValue('connectorType', initialType);
    setModalOpen(true);
    setSearchParams({}, { replace: true });
  }, [form, requestedConnectorType, setSearchParams, shouldOpenCreate]);

  const openUpdate = (credential: ConnectorCredential) => {
    setEditing(credential);
    form.resetFields();
    form.setFieldsValue({
      name: credential.name,
      connectorType: credential.connectorType,
      description: credential.description,
    });
    setModalOpen(true);
  };

  const buildInput = (values: CredentialFormValue): ConnectorCredentialInput => {
    const reserved = new Set(['name', 'connectorType', 'description']);
    const secretData = Object.fromEntries(
      Object.entries(values)
        .filter(([key, value]) => !reserved.has(key) && typeof value === 'string' && value.trim())
        .map(([key, value]) => [key, String(value).trim()])
    );
    return {
      name: values.name,
      connectorType: values.connectorType,
      description: values.description,
      secretData,
    };
  };

  const save = async () => {
    const values = await form.validateFields();
    const input = buildInput(values);
    if (Object.keys(input.secretData).length === 0) {
      message.warning('请至少填写一项连接信息');
      return;
    }
    setSaving(true);
    try {
      if (editing) {
        await updateCredential(editing.id, input);
      } else {
        await createCredential(input);
      }
      message.success(editing ? '凭证已重新加密保存' : '凭证已加密保存');
      setModalOpen(false);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '凭证保存失败');
    } finally {
      setSaving(false);
    }
  };

  const remove = async (credential: ConnectorCredential) => {
    setDeletingId(credential.id);
    try {
      await deleteCredential(credential.id);
      message.success('凭证已删除');
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '凭证删除失败');
    } finally {
      setDeletingId(undefined);
    }
  };

  return (
    <OpsLayout
      eyebrow="Security"
      title="凭证管理"
      description="API Token、数据库密码和连接信息均采用 AES-GCM 加密"
      actions={(
        <>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button icon={<PlusOutlined />} onClick={openCreate}>添加凭证</Button>
        </>
      )}
    >
      <section className="ops-data-panel">
        {loading ? (
          <div className="ops-page-loading"><Spin /></div>
        ) : credentials.length === 0 ? (
          <Empty description="暂无加密凭证">
            <Button type="primary" onClick={openCreate}>添加第一个凭证</Button>
          </Empty>
        ) : (
          <div className="ops-credential-grid">
            {credentials.map((credential) => (
              <article className="ops-credential-item" key={credential.id}>
                <span className="ops-credential-icon"><KeyOutlined /></span>
                <div className="ops-credential-main">
                  <div>
                    <h2>{credential.name}</h2>
                    <Tag>{credential.connectorType}</Tag>
                  </div>
                  <p>{credential.description || '未填写说明'}</p>
                  <div className="ops-secret-fields">
                    {credential.secretFields.map((field) => <code key={field}>{field}</code>)}
                  </div>
                  <small>更新于 {formatDateTime(credential.updatedAt)}</small>
                </div>
                <div className="ops-credential-actions">
                  <Button size="small" onClick={() => openUpdate(credential)}>更换密钥</Button>
                  <Popconfirm
                    title="删除加密凭证？"
                    description="引用此凭证的 Runbook 将无法执行。"
                    okText="删除"
                    cancelText="取消"
                    onConfirm={() => remove(credential)}
                  >
                    <Button
                      danger
                      type="text"
                      icon={<DeleteOutlined />}
                      loading={deletingId === credential.id}
                      title="删除"
                    />
                  </Popconfirm>
                </div>
              </article>
            ))}
          </div>
        )}
      </section>

      <Modal
        title={editing ? '更换加密凭证' : '添加加密凭证'}
        open={modalOpen}
        okText="加密保存"
        confirmLoading={saving}
        onOk={save}
        onCancel={() => setModalOpen(false)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" requiredMark={false}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：生产集群只读账号" />
          </Form.Item>
          <Form.Item
            name="connectorType"
            label="连接器类型"
            rules={[{ required: true, message: '请选择连接器类型' }]}
          >
            <Select options={connectorTypes} disabled={Boolean(editing)} />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input placeholder="用途和环境" />
          </Form.Item>

          {connectorType === 'mysql' && (
            <>
              <Form.Item name="jdbcUrl" label="JDBC 地址" rules={[{ required: true }]}>
                <Input placeholder="jdbc:mysql://mysql:3306/app" />
              </Form.Item>
              <Form.Item name="username" label="用户名" rules={[{ required: true }]}>
                <Input autoComplete="off" />
              </Form.Item>
              <Form.Item name="password" label="密码" rules={[{ required: true }]}>
                <Input.Password autoComplete="new-password" />
              </Form.Item>
            </>
          )}

          {connectorType === 'kubernetes' && (
            <>
              <Form.Item
                name="endpoint"
                label="Kubernetes API 地址"
                rules={[{ required: true, message: '请输入 Kubernetes API 地址' }]}
              >
                <Input placeholder="https://kubernetes.example.com:6443" />
              </Form.Item>
              <Form.Item name="namespace" label="默认命名空间">
                <Input placeholder="default" />
              </Form.Item>
              <Form.Item
                name="bearerToken"
                label="ServiceAccount Bearer Token"
                rules={[{ required: true, message: '请输入最小权限 ServiceAccount Token' }]}
              >
                <Input.Password autoComplete="new-password" />
              </Form.Item>
              <Form.Item name="caCert" label="集群 CA 证书（可选留存）">
                <Input.TextArea rows={4} placeholder="-----BEGIN CERTIFICATE-----" />
              </Form.Item>
              <Form.Item
                name="kubeconfig"
                label="kubeconfig（可选留存）"
                extra="当前执行节点使用上面的 API 地址和 Token；kubeconfig 会加密保存，便于后续管理。"
              >
                <Input.TextArea rows={5} placeholder="apiVersion: v1" />
              </Form.Item>
            </>
          )}

          {(connectorType === 'prometheus' || connectorType === 'loki') && (
            <>
              <Form.Item
                name="endpoint"
                label="服务地址"
                rules={[{ required: true, message: '请输入服务地址' }]}
              >
                <Input placeholder={connectorType === 'prometheus' ? 'https://prometheus.example.com' : 'https://loki.example.com'} />
              </Form.Item>
              <Form.Item name="bearerToken" label="Bearer Token（可选）">
                <Input.Password autoComplete="new-password" />
              </Form.Item>
              <Form.Item name="username" label="Basic Auth 用户名（可选）">
                <Input autoComplete="off" />
              </Form.Item>
              <Form.Item name="password" label="Basic Auth 密码（可选）">
                <Input.Password autoComplete="new-password" />
              </Form.Item>
              {connectorType === 'loki' && (
                <Form.Item name="tenantId" label="Loki 租户 ID（可选）">
                  <Input placeholder="X-Scope-OrgID" />
                </Form.Item>
              )}
            </>
          )}

          {connectorType === 'minio' && (
            <>
              <Form.Item name="endpoint" label="MinIO / S3 地址" rules={[{ required: true }]}>
                <Input placeholder="https://minio.example.com:9000" />
              </Form.Item>
              <Form.Item name="accessKey" label="Access Key" rules={[{ required: true }]}>
                <Input autoComplete="off" />
              </Form.Item>
              <Form.Item name="secretKey" label="Secret Key" rules={[{ required: true }]}>
                <Input.Password autoComplete="new-password" />
              </Form.Item>
              <Form.Item name="bucket" label="默认 Bucket">
                <Input placeholder="paiops" />
              </Form.Item>
              <Form.Item name="useSsl" label="使用 HTTPS">
                <Select options={[{ value: 'true', label: '是' }, { value: 'false', label: '否' }]} />
              </Form.Item>
            </>
          )}

          {(connectorType === 'webhook' || connectorType === 'alertmanager') && (
            <>
              <Form.Item
                name="endpoint"
                label={connectorType === 'webhook' ? 'Webhook 地址' : 'Alertmanager 地址'}
                rules={[{ required: true, message: '请输入服务地址' }]}
              >
                <Input placeholder="https://service.example.com" />
              </Form.Item>
              <Form.Item name="authorization" label="Authorization（可选）">
                <Input.Password autoComplete="new-password" placeholder="Bearer ..." />
              </Form.Item>
              <Form.Item name="username" label="用户名（可选）">
                <Input autoComplete="off" />
              </Form.Item>
              <Form.Item name="password" label="密码（可选）">
                <Input.Password autoComplete="new-password" />
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>
    </OpsLayout>
  );
};

export default CredentialsPage;
