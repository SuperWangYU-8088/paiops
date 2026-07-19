import api from '../utils/request';
import { ApiResult } from './workflow';
import { buildBackendUrl } from '../config/api';
import { clearStoredAuth, ensureValidAccessToken } from '../utils/auth';

export interface OpsAlert {
  id: number;
  fingerprint: string;
  alertName: string;
  severity: string;
  status: string;
  source: string;
  summary?: string;
  labels?: string;
  annotations?: string;
  incidentId?: number;
  startsAt?: string;
  endsAt?: string;
  receivedAt?: string;
  updatedAt?: string;
}

export interface OpsIncident {
  id: number;
  title: string;
  severity: string;
  status: string;
  summary?: string;
  assignee?: string;
  alertCount: number;
  runbookId?: number;
  executionId?: number;
  rootCause?: string;
  resolution?: string;
  postmortem?: string;
  startedAt?: string;
  resolvedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ExecutionTask {
  executionId: number;
  flowId: number;
  workflowName?: string;
  status: string;
  executionMode?: string;
  workerId?: string;
  attempt?: number;
  duration?: number;
  errorMessage?: string;
  queuedAt?: string;
  startedAt?: string;
  completedAt?: string;
  heartbeatAt?: string;
  executedAt?: string;
}

export interface ApprovalRequest {
  id: number;
  executionId: number;
  flowId: number;
  nodeId: string;
  nodeName?: string;
  riskLevel: string;
  status: string;
  requestedBy?: string;
  reviewedBy?: string;
  requestReason?: string;
  reviewComment?: string;
  requestedAt?: string;
  reviewedAt?: string;
  expiresAt?: string;
}

export interface AuditLog {
  id: number;
  actor: string;
  action: string;
  resourceType: string;
  resourceId?: string;
  result: string;
  detail?: string;
  ipAddress?: string;
  createdAt: string;
}

export interface ConnectorCredential {
  id: number;
  name: string;
  connectorType: string;
  description?: string;
  keyVersion: string;
  secretFields: string[];
  createdAt?: string;
  updatedAt?: string;
}

export interface ConnectorCredentialInput {
  name: string;
  connectorType: string;
  description?: string;
  secretData: Record<string, string>;
}

export const getAlerts = (status?: string): Promise<ApiResult<OpsAlert[]>> =>
  api.get('/api/alerts', { params: { status, limit: 200 } });

export const getIncidents = (status?: string): Promise<ApiResult<OpsIncident[]>> =>
  api.get('/api/incidents', { params: { status, limit: 200 } });

export const updateIncident = (
  id: number,
  data: {
    status?: string;
    assignee?: string;
    rootCause?: string;
    resolution?: string;
    postmortem?: string;
  }
): Promise<ApiResult<OpsIncident>> => api.patch(`/api/incidents/${id}`, data);

export const executeIncidentRunbook = (
  id: number,
  data: { runbookId: number; inputData?: string; idempotencyKey?: string }
): Promise<ApiResult<ExecutionTask>> => api.post(`/api/incidents/${id}/execute`, data);

export const getExecutionTasks = (status?: string): Promise<ApiResult<ExecutionTask[]>> =>
  api.get('/api/executions', { params: { status, limit: 200 } });

export const getExecutionTask = (id: number): Promise<ApiResult<ExecutionTask>> =>
  api.get(`/api/executions/${id}`);

export const submitExecutionTask = (
  workflowId: number,
  inputData: string,
  idempotencyKey?: string
): Promise<ApiResult<ExecutionTask>> =>
  api.post(`/api/workflows/${workflowId}/tasks`, { inputData, idempotencyKey });

export const cancelExecutionTask = (id: number): Promise<ApiResult<void>> =>
  api.post(`/api/executions/${id}/cancel`);

/**
 * EventSource 不能设置 Authorization 请求头，因此先用 JWT 换取 60 秒一次性票据。
 * URL 中不再出现长效访问令牌，降低代理日志和浏览器历史泄露风险。
 */
export const streamExecutionTask = async (
  executionId: number,
  onEvent: (event: import('./workflow').ExecutionEvent) => void,
  onComplete: () => void,
  onError: (error: Error) => void
) => {
  const token = await ensureValidAccessToken();
  if (!token) {
    clearStoredAuth();
    window.location.href = '/login';
    onError(new Error('登录状态已过期'));
    return null;
  }

  const ticketResult = await api.post('/api/auth/sse-ticket') as ApiResult<{ ticket: string }>;
  const url = buildBackendUrl(
    `/api/executions/${executionId}/stream?ticket=${encodeURIComponent(ticketResult.data.ticket)}`
  );
  const eventSource = new EventSource(url);
  const eventNames = [
    'WORKFLOW_START',
    'NODE_START',
    'NODE_SUCCESS',
    'NODE_PROGRESS',
    'NODE_ERROR',
    'WORKFLOW_COMPLETE',
  ];

  eventNames.forEach((eventName) => {
    eventSource.addEventListener(eventName, (event) => {
      const parsed = JSON.parse((event as MessageEvent).data);
      onEvent(parsed);
      if (eventName === 'WORKFLOW_COMPLETE') {
        eventSource.close();
        onComplete();
      }
    });
  });

  eventSource.onerror = () => {
    if (eventSource.readyState === EventSource.CLOSED) {
      return;
    }
    eventSource.close();
    onError(new Error('任务进度连接中断'));
  };
  return eventSource;
};

export const getApprovals = (status?: string): Promise<ApiResult<ApprovalRequest[]>> =>
  api.get('/api/approvals', { params: { status, limit: 200 } });

export const reviewApproval = (
  id: number,
  approved: boolean,
  comment?: string
): Promise<ApiResult<ApprovalRequest>> =>
  api.post(`/api/approvals/${id}/${approved ? 'approve' : 'reject'}`, { comment });

export const getAuditLogs = (): Promise<ApiResult<AuditLog[]>> =>
  api.get('/api/audit-logs', { params: { limit: 200 } });

export const getCredentials = (): Promise<ApiResult<ConnectorCredential[]>> =>
  api.get('/api/credentials');

export const createCredential = (
  data: ConnectorCredentialInput
): Promise<ApiResult<ConnectorCredential>> => api.post('/api/credentials', data);

export const updateCredential = (
  id: number,
  data: ConnectorCredentialInput
): Promise<ApiResult<ConnectorCredential>> => api.put(`/api/credentials/${id}`, data);

export const deleteCredential = (id: number): Promise<ApiResult<void>> =>
  api.delete(`/api/credentials/${id}`);
