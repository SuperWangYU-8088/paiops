import { useEffect, useState } from 'react';
import { Button, Empty, message, Popconfirm, Spin } from 'antd';
import { DeleteOutlined, EditOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { deleteWorkflow, getWorkflows, Workflow } from '../api/workflow';
import OpsLayout from '../components/OpsLayout';
import { formatDateTime } from '../utils/format';

const RunbooksPage = () => {
  const navigate = useNavigate();
  const [workflows, setWorkflows] = useState<Workflow[]>([]);
  const [loading, setLoading] = useState(true);
  const [deletingId, setDeletingId] = useState<number>();

  const load = async () => {
    setLoading(true);
    try {
      const result = await getWorkflows();
      setWorkflows(result.code === 200 ? result.data : []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'Runbook 加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const remove = async (workflow: Workflow) => {
    setDeletingId(workflow.id);
    try {
      await deleteWorkflow(workflow.id);
      message.success('Runbook 已删除');
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'Runbook 删除失败');
    } finally {
      setDeletingId(undefined);
    }
  };

  return (
    <OpsLayout
      eyebrow="Runbooks"
      title="Runbook 目录"
      description="可审计、可审批的确定性运维流程"
    >
      {loading ? (
        <div className="ops-page-loading"><Spin /></div>
      ) : workflows.length === 0 ? (
        <div className="ops-data-panel"><Empty description="暂无 Runbook" /></div>
      ) : (
        <div className="ops-runbook-grid">
          {workflows.map((workflow) => (
            <article className="ops-runbook-card" key={workflow.id}>
              <div className="ops-runbook-card-head">
                <span className="ops-runbook-symbol">R</span>
                <span className="ops-engine-badge">{(workflow.engineType || 'dag').toUpperCase()}</span>
              </div>
              <h2>{workflow.name}</h2>
              <p>{workflow.description || '未填写 Runbook 描述'}</p>
              <div className="ops-runbook-card-meta">
                <span>#{workflow.id}</span>
                <span>{formatDateTime(workflow.updatedAt)}</span>
              </div>
              <div className="ops-runbook-card-actions">
                <Button
                  type="primary"
                  icon={<EditOutlined />}
                  onClick={() => navigate(`/editor/${workflow.id}`)}
                >
                  打开编辑器
                </Button>
                <Popconfirm
                  title="删除 Runbook？"
                  description="历史执行记录仍会保留。"
                  okText="删除"
                  cancelText="取消"
                  onConfirm={() => remove(workflow)}
                >
                  <Button
                    danger
                    type="text"
                    icon={<DeleteOutlined />}
                    loading={deletingId === workflow.id}
                    title="删除"
                  />
                </Popconfirm>
              </div>
            </article>
          ))}
        </div>
      )}
    </OpsLayout>
  );
};

export default RunbooksPage;
