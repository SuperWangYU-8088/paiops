import api from '../utils/request';

export interface MediaUploadResult {
  url: string;
  fileName: string;
  contentType: string;
  size: number;
}

interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

export const uploadWorkflowImage = (file: File): Promise<ApiResult<MediaUploadResult>> => {
  const formData = new FormData();
  formData.append('file', file);
  return api.post('/api/media/images', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
};
