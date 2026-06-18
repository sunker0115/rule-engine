export interface SceneListItem {
  tenantId: number;
  id: number;
  sceneCode: string;
  name: string;
  dominantMode: 'PUSH' | 'PULL' | 'HYBRID';
  subjectType: string;
  status: 'ACTIVE' | 'DISABLED';
  createdAt?: string;
  updatedAt?: string;
}

export interface SceneDetail extends SceneListItem {
  description?: string;
  payloadSchema?: Record<string, unknown>;
  eventTypes: string[];
  defaultParams?: Record<string, unknown>;
  decisionStrategy: string;
  createdAt?: string;
  updatedAt?: string;
}
