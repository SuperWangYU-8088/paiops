import api from '../utils/request';
import { ApiResult } from './workflow';

export interface SystemHealth {
  status: string;
  application: string;
  timestamp: string;
}

export const getSystemHealth = (): Promise<ApiResult<SystemHealth>> => {
  return api.get('/api/system/health');
};
