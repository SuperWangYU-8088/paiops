import { useCallback, useEffect, useState } from 'react';
import { Button, Empty, Form, Input, message, Modal, Select, Spin } from 'antd';
import { CheckOutlined, FileTextOutlined, PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { executeIncidentRunbook, getIncidents, OpsIncident, updateIncident } from '../api/ops';
import { getWorkflows, Workflow } from '../api/workflow';
import OpsLayout from '../components/OpsLayout';
import { OpsStatus, SeverityTag } from '../components/OpsStatus';
import { formatDateTime } from '../utils/format';

const IncidentsPage = () => {
  const [incidents, setIncidents] = useState<OpsIncident[]>([]);
  const [status, setStatus] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [updatingId, setUpdatingId] = useState<number>();
  const [runbooks, setRunbooks] = useState<Workflow[]>([]);
  const [executeTarget, setExecuteTarget] = useState<OpsIncident>();
  const [reviewTarget, setReviewTarget] = useState<OpsIncident>();
  const [executing, setExecuting] = useState(false);
  const [savingReview, setSavingReview] = useState(false);
  const [executeForm] = Form.useForm();
  const [reviewForm] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getIncidents(status);
      setIncidents(result.code === 200 ? result.data : []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '事件加载失败');
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => {
    load();
    getWorkflows().then((result) => setRunbooks(result.code === 200 ? result.data : []));
  }, [load]);

  const openExecute = (incident: OpsIncident) => {
    setExecuteTarget(incident);
    executeForm.setFieldsValue({
      runbookId: incident.runbookId,
      inputData: '',
    });
  };

  const executeRunbook = async () => {
    if (!executeTarget) return;
    const values = await executeForm.validateFields();
    setExecuting(true);
    try {
      const result = await executeIncidentRunbook(executeTarget.id, values);
      message.success(`处置任务 #${result.data.executionId} 已进入队列`);
      setExecuteTarget(undefined);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'Runbook 启动失败');
    } finally {
      setExecuting(false);
    }
  };

  const openReview = (incident: OpsIncident) => {
    setReviewTarget(incident);
    reviewForm.setFieldsValue({
      assignee: incident.assignee,
      rootCause: incident.rootCause,
      resolution: incident.resolution,
      postmortem: incident.postmortem,
    });
  };

  const saveReview = async () => {
    if (!reviewTarget) return;
    const values = await reviewForm.validateFields();
    setSavingReview(true);
    try {
      await updateIncident(reviewTarget.id, values);
      message.success('事件处置与复盘信息已保存');
      setReviewTarget(undefined);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '事件复盘保存失败');
    } finally {
      setSavingReview(false);
    }
  };

  const changeStatus = async (incident: OpsIncident, nextStatus: string) => {
    setUpdatingId(incident.id);
    try {
      const result = await updateIncident(incident.id, { status: nextStatus });
      if (result.code === 200) {
        message.success(nextStatus === 'RESOLVED' ? '事件已标记恢复' : '事件已确认');
        await load();
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '事件更新失败');
    } finally {
      setUpdatingId(undefined);
    }
  };

  return (
    <OpsLayout
      eyebrow="Incident Response"
      title="事件中心"
      description="跟踪告警关联事件、处理状态和责任人"
      actions={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}
    >
      <div className="ops-filterbar">
        <Select
          allowClear
          value={status}
          placeholder="全部状态"
          options={[
            { value: 'OPEN', label: '处理中' },
            { value: 'ACKNOWLEDGED', label: '已确认' },
            { value: 'MITIGATING', label: '处置中' },
            { value: 'VERIFYING', label: '待验证' },
            { value: 'RESOLVED', label: '已恢复' },
            { value: 'CLOSED', label: '已关闭' },
          ]}
          onChange={setStatus}
        />
        <span>{incidents.length} 个事件</span>
      </div>

      <section className="ops-data-panel">
        {loading ? (
          <div className="ops-page-loading"><Spin /></div>
        ) : incidents.length === 0 ? (
          <Empty description="暂无事件" />
        ) : (
          <div className="ops-data-table">
            <div className="ops-table-row ops-table-head ops-incident-columns">
              <span>事件</span><span>级别</span><span>状态</span><span>责任人</span><span>开始时间</span><span>操作</span>
            </div>
            {incidents.map((incident) => (
              <div className="ops-table-row ops-incident-columns" key={incident.id}>
                <span className="ops-table-primary">
                  <strong>{incident.title}</strong>
                  <small>{incident.summary || `关联 ${incident.alertCount} 条告警`}</small>
                </span>
                <SeverityTag severity={incident.severity} />
                <OpsStatus status={incident.status} />
                <span>{incident.assignee || '未分配'}</span>
                <span>{formatDateTime(incident.startedAt)}</span>
                <span className="ops-row-actions">
                  {incident.status === 'OPEN' && (
                    <Button
                      size="small"
                      loading={updatingId === incident.id}
                      onClick={() => changeStatus(incident, 'ACKNOWLEDGED')}
                    >
                      确认
                    </Button>
                  )}
                  {!['RESOLVED', 'CLOSED'].includes(incident.status) && (
                    <Button
                      size="small"
                      icon={<PlayCircleOutlined />}
                      disabled={!!incident.executionId}
                      onClick={() => openExecute(incident)}
                    >
                      {incident.executionId ? `任务 #${incident.executionId}` : '运行 Runbook'}
                    </Button>
                  )}
                  {!['RESOLVED', 'CLOSED'].includes(incident.status) && (
                    <Button
                      size="small"
                      icon={<CheckOutlined />}
                      loading={updatingId === incident.id}
                      onClick={() => changeStatus(incident, 'RESOLVED')}
                    >
                      恢复
                    </Button>
                  )}
                  <Button
                    size="small"
                    icon={<FileTextOutlined />}
                    onClick={() => openReview(incident)}
                  >
                    处置记录
                  </Button>
                </span>
              </div>
            ))}
          </div>
        )}
      </section>

      <Modal
        title={`为事件 #${executeTarget?.id || ''} 运行 Runbook`}
        open={!!executeTarget}
        okText="进入执行队列"
        cancelText="取消"
        confirmLoading={executing}
        onOk={executeRunbook}
        onCancel={() => setExecuteTarget(undefined)}
      >
        <Form form={executeForm} layout="vertical">
          <Form.Item
            name="runbookId"
            label="处置 Runbook"
            rules={[{ required: true, message: '请选择 Runbook' }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              options={runbooks.map((runbook) => ({
                value: runbook.id,
                label: `${runbook.name} (#${runbook.id})`,
              }))}
              placeholder="选择确定性 DAG Runbook"
            />
          </Form.Item>
          <Form.Item name="inputData" label="补充处置上下文">
            <Input.TextArea rows={5} placeholder="可选；告警、事件 ID、级别等基础上下文会自动注入。" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`事件 #${reviewTarget?.id || ''} 处置记录与复盘`}
        open={!!reviewTarget}
        width={720}
        okText="保存记录"
        cancelText="取消"
        confirmLoading={savingReview}
        onOk={saveReview}
        onCancel={() => setReviewTarget(undefined)}
      >
        <Form form={reviewForm} layout="vertical">
          <Form.Item name="assignee" label="责任人">
            <Input placeholder="值班人或处理负责人" />
          </Form.Item>
          <Form.Item name="rootCause" label="根因">
            <Input.TextArea rows={3} placeholder="记录已确认的技术根因与证据" />
          </Form.Item>
          <Form.Item name="resolution" label="处置方案">
            <Input.TextArea rows={3} placeholder="记录实际采取的变更、回滚和验证结果" />
          </Form.Item>
          <Form.Item name="postmortem" label="复盘与改进项">
            <Input.TextArea rows={5} placeholder="记录时间线、影响、有效措施、待办和责任人" />
          </Form.Item>
        </Form>
      </Modal>
    </OpsLayout>
  );
};

export default IncidentsPage;
