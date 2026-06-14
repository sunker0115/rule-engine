import type { AuditTranslation } from '../../types';

const audit: AuditTranslation = {
  title: { list: 'Audit Logs' },
  column: {
    actor: 'Actor',
    actorType: 'Actor Type',
    action: 'Action',
    targetType: 'Target Type',
    targetId: 'Target ID',
    operatedAt: 'Time',
  },
  filter: {
    targetType: 'Target Type',
    targetId: 'Target ID',
    actor: 'Actor',
    action: 'Action',
    from: 'From',
    to: 'To',
  },
  enum: {
    action: {
      CREATE: 'Create', UPDATE: 'Update', PUBLISH: 'Publish', PUBLISH_FAILED: 'Publish Failed',
      ENABLE: 'Enable', DISABLE: 'Disable', DELETE: 'Delete', IMPORT: 'Import',
    },
    targetType: { RULE: 'Rule', SCENE: 'Scene', METRIC: 'Metric', DECISION: 'Decision', JOB: 'Job' },
  },
  diff: { before: 'Before', after: 'After', expand: 'Expand', noDiff: 'No changes', renderError: 'Render Error', calcError: 'Calc Error', noSnapshot: 'No Snapshot' },
};

export default audit;
