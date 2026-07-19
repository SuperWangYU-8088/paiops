import api from '../utils/request';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  refreshToken: string;
  user: {
    username: string;
  };
}

export interface RefreshTokenRequest {
  refreshToken: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

/**
 * 用户登录
 */
export const login = (data: LoginRequest): Promise<ApiResult<LoginResponse>> => {
  return api.post('/api/auth/login', data);
};

/**
 * 用户登出
 */
export const logout = (data?: RefreshTokenRequest): Promise<ApiResult<void>> => {
  return api.post('/api/auth/logout', data);
};

/**
 * 刷新访问令牌
 */
export const refreshToken = (data: RefreshTokenRequest): Promise<ApiResult<LoginResponse>> => {
  return api.post('/api/auth/refresh', data);
};

/**
 * 获取当前用户信息
 */
export const getCurrentUser = (): Promise<ApiResult<{ username: string; authenticated: boolean }>> => {
  return api.get('/api/auth/current');
};

/** 修改成功后服务端会使全部旧令牌失效，调用方必须重新登录。 */
export const changePassword = (data: ChangePasswordRequest): Promise<ApiResult<void>> => {
  return api.post('/api/auth/change-password', data);
};
