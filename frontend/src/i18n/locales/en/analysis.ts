import type { RuleSetAnalysisTranslation } from '../../types';

const analysis: RuleSetAnalysisTranslation = {
  button: 'Rule-set Analysis',
  title: 'Rule-set Analysis',
  reanalyze: 'Re-analyze',
  empty: 'No issues found',
  loadError: 'Analysis failed',
  summaryBarTooltip: 'Open rule-set analysis',
  group: {
    incoherences: 'Incoherences',
    deadRules: 'Dead / Shadowed',
    conflicts: 'Conflicts',
    coverageGaps: 'Coverage Gaps',
    overlaps: 'Overlaps / Redundancy',
    unanalyzable: 'Not Analyzed',
  },
  unanalyzableNote: 'Grayed out — NOT "no issue". Beyond v1 precise reasoning, skipped to avoid false positives.',
  sevTag: { ERROR: 'ERROR', WARN: 'WARN', INFO: 'INFO', SKIP: 'SKIP' },
};

export default analysis;
