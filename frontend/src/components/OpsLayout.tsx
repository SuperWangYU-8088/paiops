import { ReactNode } from 'react';
import { Button } from 'antd';
import {
  AlertOutlined,
  ApiOutlined,
  AppstoreOutlined,
  AuditOutlined,
  BookOutlined,
  CheckSquareOutlined,
  DashboardOutlined,
  KeyOutlined,
  LogoutOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { NavLink, useNavigate } from 'react-router-dom';
import { logout } from '../api/auth';
import { useAuthStore } from '../store/authStore';
import { getRefreshToken } from '../utils/auth';
import AppNavigation from './AppNavigation';
import ChangePasswordModal from './ChangePasswordModal';

interface OpsLayoutProps {
  eyebrow: string;
  title: string;
  description: string;
  actions?: ReactNode;
  children: ReactNode;
}

const navigation = [
  { to: '/', label: '运维总览', icon: <DashboardOutlined />, end: true },
  { to: '/alerts', label: '告警中心', icon: <AlertOutlined /> },
  { to: '/incidents', label: '事件中心', icon: <WarningOutlined /> },
  { to: '/runbooks', label: 'Runbook 目录', icon: <AppstoreOutlined /> },
  { to: '/tasks', label: '执行任务', icon: <PlayCircleOutlined /> },
  { to: '/connectors', label: '资源与连接器', icon: <ApiOutlined /> },
  { to: '/credentials', label: '凭证管理', icon: <KeyOutlined /> },
  { to: '/approvals', label: '审批中心', icon: <CheckSquareOutlined /> },
  { to: '/audit', label: '审计日志', icon: <AuditOutlined /> },
  { to: '/knowledge', label: '知识库', icon: <BookOutlined /> },
];

const OpsLayout = ({ eyebrow, title, description, actions, children }: OpsLayoutProps) => {
  const navigate = useNavigate();
  const { username, clearAuth } = useAuthStore();

  const handleLogout = async () => {
    const refreshToken = getRefreshToken();
    if (refreshToken) {
      try {
        await logout({ refreshToken });
      } catch {
        // 后端退出失败不阻塞本地会话结束。
      }
    }
    clearAuth();
    navigate('/login');
  };

  return (
    <div className="ops-shell">
      <aside className="ops-sidebar">
        <button type="button" className="ops-sidebar-brand" onClick={() => navigate('/')}>
          <span className="ops-brand-mark">P</span>
          <span>
            <strong>PaiOps</strong>
            <small>Runbook Platform</small>
          </span>
        </button>

        <nav className="ops-sidebar-nav" aria-label="运维功能导航">
          {navigation.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) => `ops-nav-item ${isActive ? 'is-active' : ''}`}
            >
              {item.icon}
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>

        <div className="ops-sidebar-footer">
          <div className="ops-sidebar-user">
            <span className="ops-user-avatar">{(username || 'U').slice(0, 1).toUpperCase()}</span>
            <span>
              <strong>{username || '用户'}</strong>
              <small>个人工作区</small>
            </span>
          </div>
          <ChangePasswordModal compact />
          <Button type="text" icon={<LogoutOutlined />} title="退出登录" onClick={handleLogout} />
        </div>
      </aside>

      <div className="ops-content-shell">
        <header className="ops-content-header">
          <div className="ops-content-title">
            <p>{eyebrow}</p>
            <h1>{title}</h1>
            <span>{description}</span>
          </div>
          <div className="ops-content-actions">
            <AppNavigation />
            {actions}
            <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/editor')}>
              新建 Runbook
            </Button>
          </div>
        </header>
        <main className="ops-content">{children}</main>
      </div>
    </div>
  );
};

export default OpsLayout;
