import type { LineageTranslation } from '../../types';

const lineage: LineageTranslation = {
  drawerTitle: '产出 {{code}} 的规则',
  metricDrawerTitle: '引用 {{code}} 的规则',
  count: '共 {{n}} 条',
  empty: '暂无规则引用，可安全停用 / 下线',
  toEditor: '进编辑器',
  badge: '{{n}} 条',
  col: { ruleCode: '规则', ruleName: '名称', scene: '场景', status: '状态' },
  editorChip: '还被 {{n}} 条引用',
  disableGuardTitle: '该 Decision 仍被以下规则产出',
  disableGuardConfirm: '仍要停用',
};

export default lineage;
