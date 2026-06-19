/** 决策效果聚合维度。 */
export type EffectivenessDimension = 'RULE_VERSION' | 'DECISION';

/** 时间分桶（漂移序列）。 */
export type EffectivenessBucket = 'NONE' | 'DAY' | 'WEEK';

/** 单维度键的混淆矩阵 + 指标；precision/recall 分母为 0 时为 null。 */
export interface EffectivenessRow {
  dimensionKey: string;
  tp: number;
  fp: number;
  fn: number;
  tn: number;
  precision: number | null;
  recall: number | null;
  fireRate: number;
  firedTotal: number;
}

/** 单时间桶报表；含诚实回报口径（unlabeled / blocked 不入指标分母）。 */
export interface BucketReport {
  bucket: string | null;
  totalSessions: number;
  labeledCount: number;
  unlabeledCount: number;
  blockedCount: number;
  totalPositive: number;
  totalNegative: number;
  rows: EffectivenessRow[];
}

/** 聚合报表：按桶分组（NONE 时单桶 bucket=null）。 */
export interface EffectivenessReport {
  buckets: BucketReport[];
}

/** 效果聚合查询参数（对齐 OutcomeController.effectiveness）。 */
export interface EffectivenessParams {
  tenantId: number;
  sceneCode: string;
  from: string;
  to: string;
  positiveLabels?: string[];
  dimension: EffectivenessDimension;
  bucket: EffectivenessBucket;
}

/** 单条回灌项（对齐 RecordOutcomesRequest.OutcomeItem）。 */
export interface OutcomeItem {
  eventId: string;
  outcomeLabel: string;
  outcomeValue?: number;
  labeledAt: string;
  source?: string;
  note?: string;
}

/** 标签回灌请求体（B32）。 */
export interface RecordOutcomesRequest {
  tenantId: number;
  outcomes: OutcomeItem[];
}
