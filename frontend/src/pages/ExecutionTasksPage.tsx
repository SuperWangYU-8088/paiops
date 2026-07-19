import { useCallback, useEffect, useState } from 'react';
import { Button, Drawer, Empty, message, Popconfirm, Select, Space, Spin } from 'antd';
import { EyeOutlined, ReloadOutlined, StopOutlined } from '@ant-design/icons';
import {
  cancelExecutionTask,
  ExecutionTask,
  getExecutionTasks,
} from '../api/ops';
import { ExecutionSnapshot, getExecutionSnapshots } from '../api/workflow';
import OpsLayout from '../components/OpsLayout';
import { OpsStatus } from '../components/OpsStatus';
import { formatDateTime, formatDuration } from '../utils/format';

const activeStatuses = ['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'CANCEL_REQUESTED'];

const ExecutionTasksPage = () => {
  const [tasks, setTasks] = useState<ExecutionTask[]>([]);
  const [status, setStatus] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [cancelingId, setCancelingId] = useState<number>();
  const [detailTask, setDetailTask] = useState<ExecutionTask>();
  const [snapshots, setSnapshots] = useState<ExecutionSnapshot[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);

  const load = useCallback(async (showLoading = true) => {
    if (showLoading) setLoading(true);
    try {
      const result = await getExecutionTasks(status);
      setTasks(result.code === 200 ? result.data : []);
    } catch (error) {
      if (showLoading) {
        message.error(error instanceof Error ? error.message : '执行任务加载失败');
      }
    } finally {
      if (showLoading) setLoading(false);
    }
  }, [status]);

  useEffect(() => {
    load();
    const timer = window.setInterval(() => load(false), 5000);
    return () => window.clearInterval(timer);
  }, [load]);

  const cancelTask = async (task: ExecutionTask) => {
    setCancelingId(task.executionId);
    try {
      await cancelExecutionTask(task.executionId);
      message.success('已提交取消请求');
      await load(false);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '取消任务失败');
    } finally {
      setCancelingId(undefined);
    }
  };

  const openDetail = async (task: ExecutionTask) => {
    setDetailTask(task);
    setDetailLoading(true);
    try {
      const result = await getExecutionSnapshots(task.flowId, task.executionId);
      setSnapshots(result.code === 200 ? result.data : []);
    } catch (error) {
      setSnapshots([]);
      message.error(error instanceof Error ? error.message : '节点快照加载失败');
    } finally {
      setDetailLoading(false);
    }
  };

  const formatSnapshotData = (value: unknown) => {
    if (value === undefined || value === null) return '-';
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return String(value);
    }
  };

  return (
    <OpsLayout
      eyebrow="Execution"
      title="执行任务"
      description="异步队列、Worker 心跳和 Runbook 执行状态"
      actions={<Button icon={<ReloadOutlined />} onClick={() => load()}>刷新</Button>}
    >
      <div className="ops-filterbar">
        <Select
          allowClear
          value={status}
          placeholder="全部状态"
          options={[
            { value: 'QUEUED', label: '排队中' },
            { value: 'RUNNING', label: '运行中' },
            { value: 'WAITING_APPROVAL', label: '等待审批' },
            { value: 'SUCCESS', label: '成功' },
            { value: 'FAILED', label: '失败' },
            { value: 'CANCELED', label: '已取消' },
          ]}
          onChange={setStatus}
        />
        <span>每 5 秒自动刷新 · {tasks.length} 个任务</span>
      </div>

      <section className="ops-data-panel">
        {loading ? (
          <div className="ops-page-loading"><Spin /></div>
        ) : tasks.length === 0 ? (
          <Empty description="暂无执行任务" />
        ) : (
          <div className="ops-data-table">
            <div className="ops-table-row ops-table-head ops-task-columns">
              <span>任务</span><span>状态</span><span>Worker</span><span>尝试</span><span>耗时</span><span>入队时间</span><span>操作</span>
            </div>
            {tasks.map((task) => (
              <div className="ops-table-row ops-task-columns" key={task.executionId}>
                <span className="ops-table-primary">
                  <strong>{task.workflowName || `Runbook #${task.flowId}`}</strong>
                  <small>执行 #{task.executionId} · {task.executionMode || 'ASYNC'}</small>
                </span>
                <OpsStatus status={task.status} />
                <span className="ops-mono">{task.workerId || '-'}</span>
                <span>{task.attempt ?? 0}</span>
                <span>{formatDuration(task.duration)}</span>
                <span>{formatDateTime(task.queuedAt || task.executedAt)}</span>
                <span>
                  <Space size={4}>
                    <Button
                      type="text"
                      size="small"
                      icon={<EyeOutlined />}
                      title="查看节点快照"
                      onClick={() => openDetail(task)}
                    />
                    {activeStatuses.includes(task.status) && (
                    <Popconfirm
                      title="取消执行任务？"
                      description="正在运行的节点会在安全检查点停止。"
                      okText="取消任务"
                      cancelText="返回"
                      onConfirm={() => cancelTask(task)}
                    >
                      <Button
                        danger
                        type="text"
                        size="small"
                        icon={<StopOutlined />}
                        loading={cancelingId === task.executionId}
                        title="取消"
                      />
                    </Popconfirm>
                    )}
                  </Space>
                </span>
              </div>
            ))}
          </div>
        )}
      </section>

      <Drawer
        title={detailTask
          ? `${detailTask.workflowName || `Runbook #${detailTask.flowId}`} · 执行 #${detailTask.executionId}`
          : '执行详情'}
        width={760}
        open={!!detailTask}
        onClose={() => setDetailTask(undefined)}
      >
        {detailTask && (
          <div className="ops-task-detail-summary">
            <OpsStatus status={detailTask.status} />
            <span>Worker：{detailTask.workerId || '-'}</span>
            <span>尝试：{detailTask.attempt ?? 0}</span>
            <span>耗时：{formatDuration(detailTask.duration)}</span>
            <span>入队：{formatDateTime(detailTask.queuedAt)}</span>
            {detailTask.errorMessage && <strong>{detailTask.errorMessage}</strong>}
          </div>
        )}
        {detailLoading ? (
          <div className="ops-page-loading"><Spin /></div>
        ) : snapshots.length === 0 ? (
          <Empty description="暂无节点快照" />
        ) : (
          <div className="ops-snapshot-list">
            {snapshots.map((snapshot) => (
              <section className="ops-snapshot-card" key={snapshot.id}>
                <header>
                  <span>
                    <strong>{snapshot.nodeName || snapshot.nodeType}</strong>
                    <small>{snapshot.nodeId} · 第 {snapshot.executionOrder ?? '-'} 步</small>
                  </span>
                  <OpsStatus status={snapshot.status} />
                </header>
                <div className="ops-snapshot-meta">
                  <span>耗时：{formatDuration(snapshot.duration)}</span>
                  <span>重试：{snapshot.retryCount ?? 0}</span>
                  <span>开始：{formatDateTime(snapshot.startedAt)}</span>
                </div>
                {snapshot.errorMessage && <div className="ops-snapshot-error">{snapshot.errorMessage}</div>}
                <details>
                  <summary>查看输入</summary>
                  <pre>{formatSnapshotData(snapshot.inputData)}</pre>
                </details>
                <details>
                  <summary>查看输出 / 变更快照</summary>
                  <pre>{formatSnapshotData(snapshot.outputData)}</pre>
                </details>
              </section>
            ))}
          </div>
        )}
      </Drawer>
    </OpsLayout>
  );
};

export default ExecutionTasksPage;
