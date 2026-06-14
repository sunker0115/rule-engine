import { create } from 'zustand';
import apiClient from '@/api/client';
import { ENDPOINTS } from '@/constants/api-endpoints';
import type { SceneListItem, SceneDetail, SceneMetadata } from '@/types';

interface SceneState {
  list: SceneListItem[];
  current: SceneDetail | null;
  metadata: SceneMetadata | null;
  loading: boolean;
  loadList: (tenantId: number) => Promise<void>;
  loadDetail: (tenantId: number, sceneCode: string) => Promise<void>;
  loadMetadata: (tenantId: number, sceneCode: string) => Promise<void>;
  clearCurrent: () => void;
}

export const useSceneStore = create<SceneState>((set) => ({
  list: [],
  current: null,
  metadata: null,
  loading: false,

  loadList: async (tenantId: number) => {
    set({ loading: true });
    const res = await apiClient.get(ENDPOINTS.SCENE_LIST, { params: { tenantId } });
    set({ list: res.data?.data ?? [], loading: false });
  },

  loadDetail: async (tenantId: number, sceneCode: string) => {
    const res = await apiClient.get(ENDPOINTS.SCENE_DETAIL(sceneCode), { params: { tenantId } });
    set({ current: res.data?.data ?? null });
  },

  loadMetadata: async (tenantId: number, sceneCode: string) => {
    const res = await apiClient.get(ENDPOINTS.SCENE_METADATA(sceneCode), { params: { tenantId } });
    set({ metadata: res.data?.data ?? null });
  },

  clearCurrent: () => set({ current: null, metadata: null }),
}));
