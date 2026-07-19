import { useEffect, useMemo, useState } from 'react';
import { Button, Empty, message, Spin } from 'antd';
import {
  AlertOutlined,
  CheckSquareOutlined,
  PlayCircleOutlined,
  SafetyCertificateOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { getSystemHealth, SystemHealth } from '../api/system';
import {
  ExecutionTask,
  getAlerts,
  getApprovals,
  getExecutionTasks,
  getIncidents,
  OpsAlert,
  OpsIncident,
} from '../api/ops';
import { getWorkflows, Workflow } from '../api/workflow';
import OpsLayout from '../components/OpsLayout';
import { OpsStatus, SeverityTag } from '../components/OpsStatus';
import { formatDateTime, formatDuration } from '../utils/format';

interface DashboardData {
  alerts: OpsAlert[];
  incidents: OpsIncident[];
  tasks: ExecutionTask[];
  pendingApprovals: number;
  workflows: Workflow[];
  health: SystemHealth | null;
}

const initialData: DashboardData = {
  alerts: [],
  incidents: [],
  tasks: [],
  pendingApprovals: 0,
  workflows: [],
  health: null,
};

const OpsDashboardPage = () => {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState<DashboardData>(initialData);

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      try {
        const [alerts, incidents, tasks, approvals, workflows, health] = await Promise.all([
          getAlerts(),
          getIncidents(),
          getExecutionTasks(),
          getApprovals('PENDING'),
          getWorkflows(),
          getSystemHealth().catch(() => null),
        ]);
        setData({
          alerts: alerts.code === 200 ? alerts.data : [],
          incidents: incidents.code === 200 ? incidents.data : [],
          tasks: tasks.code === 200 ? tasks.data : [],
          pendingApprovals: approvals.code === 200 ? approvals.data.length : 0,
          workflows: workflows.code === 200 ? workflows.data : [],
          health: health?.code === 200 ? health.data : null,
        });
      } catch (error) {
        message.error(error instanceof Error ? error.message : '运维总览加载失败');
      } finally {
        setLoading(false);
      }
    };
    load();
  }, []);

  const activeAlerts = useMemo(
    () => data.alerts.filter((alert) => alert.status === 'FIRING'),
    [data.alerts]
  );
  const openIncidents = useMemo(
    () => data.incidents.filter((incident) => !['RESOLVED', 'CLOSED'].includes(incident.status)),
    [data.incidents]
  );
  const activeTasks = useMemo(
    () => data.tasks.filter((task) => ['QUEUED', 'RUNNING', 'WAITING_APPROVAL'].includes(task.status)),
    [data.tasks]
  );

  return (
    <OpsLayout
      eyebrow="Operations"
      title="运维总览"
      description="告警、事件、执行任务与审批状态"
      actions={(
        <div className={`ops-health-chip ${data.health?.status === 'UP' ? 'is-up' : ''}`}>
          <SafetyCertificateOutlined />
          <span>{data.health?.status === 'UP' ? '控制面正常' : '控制面待确认'}</span>
        </div>
      )}
    >
      {loading ? (
        <div className="ops-page-loading"><Spin /></div>
      ) : (
        <>
          <section className="ops-metric-grid">
            <button type="button" className="ops-summary-metric is-danger" onClick={() => navigate('/alerts')}>
              <span className="ops-summary-icon"><AlertOutlined /></span>
              <span>
                <small>活跃告警</small>
                <strong>{activeAlerts.length}</strong>
              </span>
            </button>
            <button type="button" className="ops-summary-metric is-warning" onClick={() => navigate('/incidents')}>
              <span className="ops-summary-icon"><WarningOutlined /></span>
              <span>
                <small>未关闭事件</small>
                <strong>{openIncidents.length}</strong>
              </span>
            </button>
            <button type="button" className="ops-summary-metric is-running" onClick={() => navigate('/tasks')}>
              <span className="ops-summary-icon"><PlayCircleOutlined /></span>
              <span>
                <small>活动任务</small>
                <strong>{activeTasks.length}</strong>
              </span>
            </button>
            <button type="button" className="ops-summary-metric is-approval" onClick={() => navigate('/approvals')}>
              <span className="ops-summary-icon"><CheckSquareOutlined /></span>
              <span>
                <small>待审批</small>
                <strong>{data.pendingApprovals}</strong>
              </span>
            </button>
          </section>

          <section className="ops-dashboard-grid">
            <div className="ops-panel">
              <div className="ops-panel-heading">
                <div><h2>最近事件</h2><p>按更新时间排序</p></div>
                <Button type="link" onClick={() => navigate('/incidents')}>查看全部</Button>
              </div>
              {data.incidents.length === 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无事件" />
              ) : (
                <div className="ops-compact-list">
                  {data.incidents.slice(0, 6).map((incident) => (
                    <button type="button" key={incident.id} onClick={() => navigate('/incidents')}>
                      <span className="ops-list-main">
                        <strong>{incident.title}</strong>
                        <small>{incident.summary || `事件 #${incident.id}`}</small>
                      </span>
                      <SeverityTag severity={incident.severity} />
                      <OpsStatus status={incident.status} />
                    </button>
                  ))}
                </div>
              )}
            </div>

            <div className="ops-panel">
              <div className="ops-panel-heading">
                <div><h2>最近任务</h2><p>Worker 执行状态</p></div>
                <Button type="link" onClick={() => navigate('/tasks')}>查看全部</Button>
              </div>
              {data.tasks.length === 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无执行任务" />
              ) : (
                <div className="ops-compact-list">
                  {data.tasks.slice(0, 6).map((task) => (
                    <button type="button" key={task.executionId} onClick={() => navigate('/tasks')}>
                      <span className="ops-list-main">
                        <strong>{task.workflowName || `Runbook #${task.flowId}`}</strong>
                        <small>任务 #{task.executionId} · {formatDateTime(task.queuedAt || task.executedAt)}</small>
                      </span>
                      <span className="ops-list-duration">{formatDuration(task.duration)}</span>
                      <OpsStatus status={task.status} />
                    </button>
                  ))}
                </div>
              )}
            </div>
          </section>

          <section className="ops-panel ops-runbook-overview">
            <div className="ops-panel-heading">
              <div><h2>Runbook 目录</h2><p>{data.workflows.length} 个已保存流程</p></div>
              <Button onClick={() => navigate('/runbooks')}>进入目录</Button>
            </div>
            <div className="ops-runbook-strip">
              {data.workflows.slice(0, 5).map((workflow) => (
                <button
                  type="button"
                  key={workflow.id}
                  onClick={() => navigate(`/editor/${workflow.id}`)}
                >
                  <span className="ops-runbook-symbol">R</span>
                  <span>
                    <strong>{workflow.name}</strong>
                    <small>{(workflow.engineType || 'dag').toUpperCase()} · {formatDateTime(workflow.updatedAt)}</small>
                  </span>
                </button>
              ))}
              {data.workflows.length === 0 && (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无 Runbook" />
              )}
            </div>
          </section>
        </>
      )}
    </OpsLayout>
  );
};

export default OpsDashboardPage;
