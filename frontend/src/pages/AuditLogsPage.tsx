import { useEffect, useState } from 'react';
import { Button, Empty, message, Spin } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { AuditLog, getAuditLogs } from '../api/ops';
import OpsLayout from '../components/OpsLayout';
import { OpsStatus } from '../components/OpsStatus';
import { formatDateTime } from '../utils/format';

const AuditLogsPage = () => {
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const result = await getAuditLogs();
      setLogs(result.code === 200 ? result.data : []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '审计日志加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  return (
    <OpsLayout
      eyebrow="Audit"
      title="审计日志"
      description="记录任务、审批、凭证和事件的关键操作"
      actions={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}
    >
      <section className="ops-data-panel">
        {loading ? (
          <div className="ops-page-loading"><Spin /></div>
        ) : logs.length === 0 ? (
          <Empty description="暂无审计记录" />
        ) : (
          <div className="ops-data-table">
            <div className="ops-table-row ops-table-head ops-audit-columns">
              <span>操作</span><span>操作者</span><span>资源</span><span>结果</span><span>来源 IP</span><span>时间</span>
            </div>
            {logs.map((log) => (
              <div className="ops-table-row ops-audit-columns" key={log.id}>
                <span className="ops-table-primary">
                  <strong>{log.action}</strong>
                  <small>{log.detail || '无附加详情'}</small>
                </span>
                <span>{log.actor}</span>
                <span>{log.resourceType}{log.resourceId ? ` #${log.resourceId}` : ''}</span>
                <OpsStatus status={log.result} />
                <span className="ops-mono">{log.ipAddress || '-'}</span>
                <span>{formatDateTime(log.createdAt)}</span>
              </div>
            ))}
          </div>
        )}
      </section>
    </OpsLayout>
  );
};

export default AuditLogsPage;
