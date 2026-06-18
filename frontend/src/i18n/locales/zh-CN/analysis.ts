import type { RuleSetAnalysisTranslation } from '../../types';

const analysis: RuleSetAnalysisTranslation = {
  button: '规则集分析',
  title: '规则集分析',
  reanalyze: '重新分析',
  empty: '未发现问题',
  loadError: '分析失败',
  summaryBarTooltip: '点击查看规则集分析',
  summaryBarHint: '点击查看分析',
  allClear: '无问题',
  group: {
    incoherences: '不一致',
    deadRules: '死规则 / 遮蔽',
    conflicts: '冲突',
    coverageGaps: '覆盖缺口',
    overlaps: '重叠 / 冗余',
    unanalyzable: '未分析',
    redundancies: '冗余条件',
  },
  unanalyzableNote: '灰显，非"无问题"——超出 v1 精确推理，已跳过以避免误判。',
  sevTag: { ERROR: 'ERROR', WARN: 'WARN', INFO: 'INFO', SKIP: 'SKIP' },
};

export default analysis;
