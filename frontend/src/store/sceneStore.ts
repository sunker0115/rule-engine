import { create } from 'zustand';
import { listScenes, getScene } from '@/api/scene';
import { getSceneMetadata } from '@/api/metadata';
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
    try { set({ list: await listScenes(tenantId) }); } finally { set({ loading: false }); }
  },

  loadDetail: async (tenantId: number, sceneCode: string) => {
    set({ current: await getScene(tenantId, sceneCode) });
  },

  loadMetadata: async (tenantId: number, sceneCode: string) => {
    set({ metadata: await getSceneMetadata(tenantId, sceneCode) });
  },

  clearCurrent: () => set({ current: null, metadata: null }),
}));
