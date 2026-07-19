import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ConfigProvider, Spin } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { lazy, ReactNode, Suspense } from 'react';
import { useAuthStore } from './store/authStore';

// 编辑器和各运维中心按路由拆包，避免首次打开登录页时下载整套 ReactFlow。
const LoginPage = lazy(() => import('./pages/LoginPage'));
const EditorPage = lazy(() => import('./pages/EditorPage'));
const KnowledgePage = lazy(() => import('./pages/KnowledgePage'));
const McpToolPage = lazy(() => import('./pages/McpToolPage'));
const OpsDashboardPage = lazy(() => import('./pages/OpsDashboardPage'));
const AlertsPage = lazy(() => import('./pages/AlertsPage'));
const IncidentsPage = lazy(() => import('./pages/IncidentsPage'));
const RunbooksPage = lazy(() => import('./pages/RunbooksPage'));
const ExecutionTasksPage = lazy(() => import('./pages/ExecutionTasksPage'));
const ConnectorsPage = lazy(() => import('./pages/ConnectorsPage'));
const CredentialsPage = lazy(() => import('./pages/CredentialsPage'));
const ApprovalsPage = lazy(() => import('./pages/ApprovalsPage'));
const AuditLogsPage = lazy(() => import('./pages/AuditLogsPage'));

const ProtectedRoute = ({ children }: { children: ReactNode }) => {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  return isAuthenticated ? children : <Navigate to="/login" replace />;
};

function App() {
  return (
    <ConfigProvider locale={zhCN}>
      <BrowserRouter>
        <Suspense fallback={<div className="app-route-loading"><Spin /></div>}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/" element={<ProtectedRoute><OpsDashboardPage /></ProtectedRoute>} />
            <Route path="/alerts" element={<ProtectedRoute><AlertsPage /></ProtectedRoute>} />
            <Route path="/incidents" element={<ProtectedRoute><IncidentsPage /></ProtectedRoute>} />
            <Route path="/runbooks" element={<ProtectedRoute><RunbooksPage /></ProtectedRoute>} />
            <Route path="/tasks" element={<ProtectedRoute><ExecutionTasksPage /></ProtectedRoute>} />
            <Route path="/connectors" element={<ProtectedRoute><ConnectorsPage /></ProtectedRoute>} />
            <Route path="/credentials" element={<ProtectedRoute><CredentialsPage /></ProtectedRoute>} />
            <Route path="/approvals" element={<ProtectedRoute><ApprovalsPage /></ProtectedRoute>} />
            <Route path="/audit" element={<ProtectedRoute><AuditLogsPage /></ProtectedRoute>} />
            <Route path="/editor" element={<ProtectedRoute><EditorPage /></ProtectedRoute>} />
            <Route path="/editor/:id" element={<ProtectedRoute><EditorPage /></ProtectedRoute>} />
            <Route path="/knowledge" element={<ProtectedRoute><KnowledgePage /></ProtectedRoute>} />
            <Route path="/mcp-tools" element={<ProtectedRoute><McpToolPage /></ProtectedRoute>} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </BrowserRouter>
    </ConfigProvider>
  );
}

export default App;
