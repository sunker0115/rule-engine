import axios from 'axios';
import { message } from 'antd';

const apiClient = axios.create({
  baseURL: '/',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
});

apiClient.interceptors.request.use((config) => {
  const actorId = localStorage.getItem('actorId') || 'anonymous';
  config.headers['X-Actor-Id'] = actorId;
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const msg = error.response?.data?.message || error.response?.statusText || '请求失败';
    message.error(msg);
    return Promise.reject(error);
  },
);

export default apiClient;
