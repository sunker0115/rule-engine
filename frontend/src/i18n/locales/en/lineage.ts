import type { LineageTranslation } from '../../types';

const lineage: LineageTranslation = {
  drawerTitle: 'Rules producing {{code}}',
  metricDrawerTitle: 'Rules referencing {{code}}',
  count: '{{n}} total',
  empty: 'No rule references — safe to disable / retire',
  toEditor: 'Open editor',
  badge: '{{n}}',
  col: { ruleCode: 'Rule', ruleName: 'Name', scene: 'Scene', status: 'Status' },
  editorChip: 'Referenced by {{n}} more',
  disableGuardTitle: 'This Decision is still produced by the following rules',
  disableGuardConfirm: 'Disable anyway',
};

export default lineage;
