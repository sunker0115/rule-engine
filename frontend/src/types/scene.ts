export interface SceneListItem {
  id: number;
  sceneCode: string;
  name: string;
  dominantMode: 'PUSH' | 'PULL' | 'HYBRID';
  subjectType: string;
  status: 'ACTIVE' | 'DISABLED';
}

export interface SceneDetail extends SceneListItem {
  tenantId: number;
  description?: string;
  payloadSchema?: Record<string, unknown>;
  eventTypes: string[];
  defaultParams?: Record<string, unknown>;
  decisionStrategy: string;
  createdAt?: string;
  updatedAt?: string;
}
