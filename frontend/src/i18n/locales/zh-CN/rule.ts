import type { RuleTranslation } from '../../types';

const rule: RuleTranslation = {
  title: { list: '规则列表', editor: '规则编辑器' },
  action: {
    create: '新建规则',
    saveDraft: '保存草稿',
    publish: '发布',
    dryRun: '试算',
    newVersion: '新版本',
    rollback: '回退到此版本',
    disable: '停用',
    enable: '启用',
    deleteDraft: '删除草稿',
    deleteRule: '删除规则',
  },
  column: {
    code: 'Code',
    name: '名称',
    kind: '类型',
    sceneCode: 'Scene',
    status: '状态',
    currentVersion: '当前版本',
    publishedAt: '发布时间',
    actions: '操作',
  },
  enum: {
    status: { DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已禁用' },
    versionStatus: { DRAFT: '草稿', ACTIVE: '生效中', SUPERSEDED: '已取代' },
    kind: {
      AST_BOOLEAN: 'AST 布尔树',
      SCORECARD: '评分卡',
      DECISION_TREE: '决策树',
      DECISION_TABLE: '决策表',
      EXPRESSION_SCRIPT: '表达式脚本',
    },
  },
  editor: {
    leftPanel: { ruleInfo: '规则信息', versionTimeline: '版本历史' },
    centerPanel: { placeholder: '编辑器 (v1.5 实装)' },
    rightPanel: {
      property: '属性',
      preGate: 'Pre-Gate',
      decisionBinding: 'Decision 绑定',
      noSelection: '选择 AST 节点查看属性',
    },
  },
  version: {
    rollbackConfirm: '将回退到 v{version}（克隆其配置、按当前世界重解析），需显式发布后生效。确认？',
    deleteDraftConfirm: '确认删除此草稿版本？',
    deleteRuleConfirm: '确认删除此规则？此操作不可撤销',
    publishConfirm: '确认发布 v{version}？发布后立即生效。',
    disableConfirm: '停用后规则从评估链路移除，确认？',
    newVersionConfirm: '将基于当前版本 v{version} 创建 v{newVersion} 草稿。确认？',
  },
};

export default rule;
