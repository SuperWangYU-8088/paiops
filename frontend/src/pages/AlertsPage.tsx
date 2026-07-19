import { useCallback, useEffect, useState } from 'react';
import { Button, Empty, message, Select, Spin } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { getAlerts, OpsAlert } from '../api/ops';
import OpsLayout from '../components/OpsLayout';
import { OpsStatus, SeverityTag } from '../components/OpsStatus';
import { formatDateTime } from '../utils/format';

const AlertsPage = () => {
  const [alerts, setAlerts] = useState<OpsAlert[]>([]);
  const [status, setStatus] = useState<string>();
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getAlerts(status);
      setAlerts(result.code === 200 ? result.data : []);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '告警加载失败');
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <OpsLayout
      eyebrow="Monitoring"
      title="告警中心"
      description="接收并归一化 Prometheus Alertmanager 告警"
      actions={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}
    >
      <div className="ops-filterbar">
        <Select
          allowClear
          value={status}
          placeholder="全部状态"
          options={[
            { value: 'FIRING', label: '告警中' },
            { value: 'RESOLVED', label: '已恢复' },
          ]}
          onChange={setStatus}
        />
        <span>{alerts.length} 条告警</span>
      </div>

      <section className="ops-data-panel">
        {loading ? (
          <div className="ops-page-loading"><Spin /></div>
        ) : alerts.length === 0 ? (
          <Empty description="暂无告警" />
        ) : (
          <div className="ops-data-table">
            <div className="ops-table-row ops-table-head ops-alert-columns">
              <span>告警</span><span>级别</span><span>来源</span><span>状态</span><span>接收时间</span>
            </div>
            {alerts.map((alert) => (
              <div className="ops-table-row ops-alert-columns" key={alert.id}>
                <span className="ops-table-primary">
                  <strong>{alert.alertName}</strong>
                  <small>{alert.summary || alert.fingerprint}</small>
                </span>
                <SeverityTag severity={alert.severity} />
                <span>{alert.source}</span>
                <OpsStatus status={alert.status} />
                <span>{formatDateTime(alert.receivedAt)}</span>
              </div>
            ))}
          </div>
        )}
      </section>
    </OpsLayout>
  );
};

export default AlertsPage;
