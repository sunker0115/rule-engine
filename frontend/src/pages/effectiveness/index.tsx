import { useEffect, useMemo, useState } from 'react';
import { Alert, Select, Space, Segmented, Button, DatePicker, Table, Descriptions, Card, Empty } from 'antd';
import { Line } from '@ant-design/plots';
import { useTranslation } from 'react-i18next';
import type { Dayjs } from 'dayjs';
import { useTenantStore } from '@/store/tenantStore';
import { listScenes } from '@/api/scene';
import { getEffectiveness } from '@/api/effectiveness';
import RecordOutcomeModal from './RecordOutcomeModal';
import type {
  SceneListItem,
  EffectivenessReport,
  EffectivenessRow,
  EffectivenessDimension,
  EffectivenessBucket,
} from '@/types';
import type { ColumnsType } from 'antd/es/table';

const { RangePicker } = DatePicker;

type ChartMetric = 'precision' | 'recall' | 'fireRate';

/** 表格扁平行：bucket × dimension row。 */
interface FlatRow extends EffectivenessRow {
  bucket: string | null;
  rowKey: string;
}

const fmtMetric = (v: number | null) => (v == null ? '—' : v.toFixed(4));

export default function EffectivenessPage() {
  const { t } = useTranslation('effectiveness');
  const tc = useTranslation('common').t;
  const { currentId, activeList, setCurrentById } = useTenantStore();

  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);
  const tenantId = tenantFilter ?? currentId ?? 0;

  const [scenes, setScenes] = useState<SceneListItem[]>([]);
  const [sceneCode, setSceneCode] = useState<string | undefined>(undefined);
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [positiveLabels, setPositiveLabels] = useState<string[]>([]);
  const [dimension, setDimension] = useState<EffectivenessDimension>('RULE_VERSION');
  const [bucket, setBucket] = useState<EffectivenessBucket>('NONE');
  const [chartMetric, setChartMetric] = useState<ChartMetric>('precision');

  const [report, setReport] = useState<EffectivenessReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);

  // 切换租户时重载场景列表并重置场景选择
  useEffect(() => {
    if (!tenantId) return;
    listScenes(tenantId).then((list) => setScenes(list ?? [])).catch(() => setScenes([]));
    setSceneCode(undefined);
  }, [tenantId]);

  const query = async () => {
    if (!tenantId || !sceneCode || !range) return;
    setLoading(true);
    try {
      const data = await getEffectiveness({
        tenantId,
        sceneCode,
        from: range[0].toISOString(),
        to: range[1].toISOString(),
        positiveLabels,
        dimension,
        bucket,
      });
      setReport(data);
    } finally {
      setLoading(false);
    }
  };

  const flatRows: FlatRow[] = useMemo(() => {
    if (!report) return [];
    return report.buckets.flatMap((b) =>
      b.rows.map((r) => ({ ...r, bucket: b.bucket, rowKey: `${b.bucket ?? '-'}|${r.dimensionKey}` })),
    );
  }, [report]);

  const showBucketCol = bucket !== 'NONE';

  const columns: ColumnsType<FlatRow> = [
    ...(showBucketCol
      ? [{ title: t('table.bucket'), dataIndex: 'bucket', key: 'bucket', width: 130, render: (v: string | null) => v ?? '-' }]
      : []),
    { title: t('table.dimensionKey'), dataIndex: 'dimensionKey', key: 'dimensionKey', ellipsis: true },
    { title: t('table.tp'), dataIndex: 'tp', key: 'tp', width: 70 },
    { title: t('table.fp'), dataIndex: 'fp', key: 'fp', width: 70 },
    { title: t('table.fn'), dataIndex: 'fn', key: 'fn', width: 70 },
    { title: t('table.tn'), dataIndex: 'tn', key: 'tn', width: 70 },
    { title: t('table.precision'), dataIndex: 'precision', key: 'precision', width: 100, render: fmtMetric },
    { title: t('table.recall'), dataIndex: 'recall', key: 'recall', width: 100, render: fmtMetric },
    { title: t('table.fireRate'), dataIndex: 'fireRate', key: 'fireRate', width: 100, render: (v: number) => v.toFixed(4) },
    { title: t('table.firedTotal'), dataIndex: 'firedTotal', key: 'firedTotal', width: 100 },
  ];

  // 漂移折线数据：仅 bucket≠NONE，跳过所选指标为 null 的点
  const chartData = useMemo(() => {
    if (!report || bucket === 'NONE') return [];
    return report.buckets.flatMap((b) =>
      b.rows
        .map((r) => ({ bucket: b.bucket ?? '-', dimensionKey: r.dimensionKey, value: r[chartMetric] }))
        .filter((d) => d.value != null),
    ) as { bucket: string; dimensionKey: string; value: number }[];
  }, [report, bucket, chartMetric]);

  const chartConfig = {
    data: chartData,
    xField: 'bucket',
    yField: 'value',
    colorField: 'dimensionKey',
    seriesField: 'dimensionKey',
    point: { shapeField: 'circle', sizeField: 3 },
    height: 320,
  };

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>{t('title')}</h2>
      <Alert type="info" showIcon message={t('valueGate')} style={{ marginBottom: 16 }} />

      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          placeholder={tc('label.tenant')}
          value={tenantFilter ?? currentId ?? undefined}
          onChange={(v) => { setTenantFilter(v); setCurrentById(v); }}
          allowClear
          options={activeList.map((tn) => ({ value: tn.id, label: `${tn.name} (${tn.code})` }))}
          style={{ width: 200 }}
        />
        <Select
          placeholder={t('filter.scenePlaceholder')}
          value={sceneCode}
          onChange={setSceneCode}
          allowClear
          showSearch
          optionFilterProp="label"
          options={scenes.map((s) => ({ value: s.sceneCode, label: `${s.name} (${s.sceneCode})` }))}
          style={{ width: 220 }}
        />
        <RangePicker
          showTime
          style={{ width: 340 }}
          value={range}
          onChange={(dates) => setRange(dates && dates[0] && dates[1] ? [dates[0], dates[1]] : null)}
        />
        <Select
          mode="tags"
          placeholder={t('filter.positiveLabelsPlaceholder')}
          value={positiveLabels}
          onChange={setPositiveLabels}
          style={{ minWidth: 220 }}
          tokenSeparators={[',', ' ']}
          onBlur={(e) => {
            // 用户点击别处时把输入框里未确认的文字自动转成标签
            const raw = (e.target as HTMLInputElement).value?.trim();
            if (raw && !positiveLabels.includes(raw)) {
              setPositiveLabels([...positiveLabels, raw]);
            }
          }}
        />
        <Segmented<EffectivenessDimension>
          value={dimension}
          onChange={setDimension}
          options={[
            { value: 'RULE_VERSION', label: t('dimension.RULE_VERSION') },
            { value: 'DECISION', label: t('dimension.DECISION') },
          ]}
        />
        <Segmented<EffectivenessBucket>
          value={bucket}
          onChange={setBucket}
          options={[
            { value: 'NONE', label: t('bucket.NONE') },
            { value: 'DAY', label: t('bucket.DAY') },
            { value: 'WEEK', label: t('bucket.WEEK') },
          ]}
        />
        <Button type="primary" onClick={query} loading={loading} disabled={!tenantId || !sceneCode || !range}>
          {t('filter.query')}
        </Button>
        <Button onClick={() => setModalOpen(true)} disabled={!tenantId}>
          {t('filter.backfill')}
        </Button>
      </Space>

      {report && report.buckets.length > 0 ? (
        <>
          <Card size="small" title={t('banner.title')} style={{ marginBottom: 16 }}>
            {report.buckets.map((b, idx) => (
              <Descriptions
                key={b.bucket ?? `bucket-${idx}`}
                size="small"
                column={6}
                title={showBucketCol ? b.bucket ?? '-' : undefined}
                style={{ marginBottom: idx < report.buckets.length - 1 ? 12 : 0 }}
              >
                <Descriptions.Item label={t('banner.totalSessions')}>{b.totalSessions}</Descriptions.Item>
                <Descriptions.Item label={t('banner.labeled')}>{b.labeledCount}</Descriptions.Item>
                <Descriptions.Item label={t('banner.unlabeled')}>{b.unlabeledCount}</Descriptions.Item>
                <Descriptions.Item label={t('banner.blocked')}>{b.blockedCount}</Descriptions.Item>
                <Descriptions.Item label={t('banner.positive')}>{b.totalPositive}</Descriptions.Item>
                <Descriptions.Item label={t('banner.negative')}>{b.totalNegative}</Descriptions.Item>
              </Descriptions>
            ))}
            <Alert type="warning" showIcon message={t('banner.note')} style={{ marginTop: 12 }} />
          </Card>

          <Card size="small" title={t('table.title')} style={{ marginBottom: 16 }}>
            <Table
              columns={columns}
              dataSource={flatRows}
              rowKey="rowKey"
              size="small"
              pagination={false}
              scroll={{ x: 'max-content' }}
            />
          </Card>

          {showBucketCol && (
            <Card
              size="small"
              title={t('chart.title')}
              extra={
                <Segmented<ChartMetric>
                  value={chartMetric}
                  onChange={setChartMetric}
                  options={[
                    { value: 'precision', label: t('chart.precision') },
                    { value: 'recall', label: t('chart.recall') },
                    { value: 'fireRate', label: t('chart.fireRate') },
                  ]}
                />
              }
            >
              {chartData.length > 0 ? <Line {...chartConfig} /> : <Empty description={t('empty')} />}
            </Card>
          )}
        </>
      ) : (
        report && <Empty description={t('empty')} />
      )}

      <RecordOutcomeModal open={modalOpen} tenantId={tenantId} onClose={() => setModalOpen(false)} />
    </div>
  );
}
