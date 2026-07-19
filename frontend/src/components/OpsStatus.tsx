import { Tag } from 'antd';
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  MinusCircleOutlined,
  SyncOutlined,
} from '@ant-design/icons';

const statusMap: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  SUCCESS: { label: '成功', color: 'success', icon: <CheckCircleOutlined /> },
  FAILED: { label: '失败', color: 'error', icon: <CloseCircleOutlined /> },
  RUNNING: { label: '运行中', color: 'processing', icon: <SyncOutlined spin /> },
  QUEUED: { label: '排队中', color: 'default', icon: <ClockCircleOutlined /> },
  WAITING_APPROVAL: { label: '等待审批', color: 'warning', icon: <ClockCircleOutlined /> },
  CANCEL_REQUESTED: { label: '取消中', color: 'warning', icon: <ClockCircleOutlined /> },
  CANCELED: { label: '已取消', color: 'default', icon: <MinusCircleOutlined /> },
  REJECTED: { label: '已拒绝', color: 'error', icon: <CloseCircleOutlined /> },
  PENDING: { label: '待处理', color: 'warning', icon: <ClockCircleOutlined /> },
  APPROVED: { label: '已批准', color: 'success', icon: <CheckCircleOutlined /> },
  RESOLVED: { label: '已恢复', color: 'success', icon: <CheckCircleOutlined /> },
  CLOSED: { label: '已关闭', color: 'default', icon: <MinusCircleOutlined /> },
  OPEN: { label: '处理中', color: 'error', icon: <ClockCircleOutlined /> },
  ACKNOWLEDGED: { label: '已确认', color: 'processing', icon: <ClockCircleOutlined /> },
  FIRING: { label: '告警中', color: 'error', icon: <CloseCircleOutlined /> },
};

export const OpsStatus = ({ status }: { status?: string }) => {
  const normalized = (status || 'UNKNOWN').toUpperCase();
  const meta = statusMap[normalized] || {
    label: status || '未知',
    color: 'default',
    icon: <MinusCircleOutlined />,
  };
  return <Tag color={meta.color} icon={meta.icon}>{meta.label}</Tag>;
};

export const SeverityTag = ({ severity }: { severity?: string }) => {
  const normalized = (severity || 'info').toLowerCase();
  const color = normalized === 'critical'
    ? 'error'
    : normalized === 'warning'
      ? 'warning'
      : 'blue';
  const label = normalized === 'critical' ? '严重' : normalized === 'warning' ? '警告' : '信息';
  return <Tag color={color}>{label}</Tag>;
};
