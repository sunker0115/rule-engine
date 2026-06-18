import { useMemo } from 'react';
import { Drawer, Collapse, Tag, Button, Empty, Spin, Space, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { RuleSetAnalysisReport, Severity } from '@/types';

interface Props {
  open: boolean;
  onClose: () => void;
  sceneCode: string;
  report: RuleSetAnalysisReport | null;
  loading: boolean;
  /** 重新分析（重新拉取报告）。 */
  onReanalyze: () => void;
  /** 点击某条 finding 时定位到对应规则（传 loc/ruleCode 的规则码部分）。 */
  onLocate: (ruleCode: string) => void;
}

/** AntD Tag 颜色：ERROR 红 / WARN 橙 / INFO 蓝 / 未分析 灰。 */
const SEV_COLOR: Record<Severity, string> = {
  ERROR: 'error',
  WARN: 'orange',
  INFO: 'blue',
};

/** loc 形如 "R1" 或 "R1#row2"——取 # 前的规则码部分用于定位。 */
function ruleCodeOf(loc: string): string {
  const i = loc.indexOf('#');
  return i >= 0 ? loc.slice(0, i) : loc;
}

interface FindingRow {
  key: string;
  title: string;
  reason: string;
  /** 点击定位用的规则码；缺口类（仅 decisionCode）无定位目标则为 null。 */
  locate: string | null;
}

export default function RuleAnalysisDrawer({ open, onClose, sceneCode, report, loading, onReanalyze, onLocate }: Props) {
  const t = useTranslation('analysis').t;

  /** 按 6 类组织 finding 行 + 每组的最坏严重度。 */
  const groups = useMemo(() => {
    if (!report) return null;
    const incoherences: FindingRow[] = report.incoherences.map((x, i) => ({
      key: `inc-${i}`,
      title: x.ruleCode,
      reason: x.reason,
      locate: x.ruleCode,
    }));
    const deadRules: FindingRow[] = report.deadRules.map((x, i) => ({
      key: `dead-${i}`,
      title: `${x.deadRuleCode} ← ${x.coveredByRuleCode}`,
      reason: x.reason,
      locate: x.deadRuleCode,
    }));
    const conflicts: FindingRow[] = report.conflicts.map((x, i) => ({
      key: `conf-${i}`,
      title: `${x.locA} × ${x.locB} : ${x.decisionA} ↔ ${x.decisionB}`,
      reason: x.reason,
      locate: ruleCodeOf(x.locA),
    }));
    const coverageGaps: FindingRow[] = report.coverageGaps.map((x, i) => ({
      key: `gap-${i}`,
      title: x.decisionCode,
      reason: x.reason,
      locate: null,
    }));
    const overlaps: FindingRow[] = report.overlaps.map((x, i) => ({
      key: `ovl-${i}`,
      title: `${x.locA} × ${x.locB}`,
      reason: x.reason,
      locate: ruleCodeOf(x.locA),
    }));
    const redundancies: FindingRow[] = report.redundancies.map((x, i) => ({
      key: `red-${i}`,
      title: `${x.ruleCode} : ${x.redundantCondition} ⇐ ${x.impliedByCondition}`,
      reason: x.reason,
      locate: x.ruleCode,
    }));
    const unanalyzable: FindingRow[] = report.unanalyzableRules.map((x, i) => ({
      key: `na-${i}`,
      title: x.ruleCode,
      reason: x.reason,
      locate: x.ruleCode,
    }));
    return { incoherences, deadRules, conflicts, coverageGaps, overlaps, redundancies, unanalyzable };
  }, [report]);

  const totalFindings =
    (report?.incoherences.length ?? 0) +
    (report?.deadRules.length ?? 0) +
    (report?.conflicts.length ?? 0) +
    (report?.coverageGaps.length ?? 0) +
    (report?.overlaps.length ?? 0) +
    (report?.redundancies.length ?? 0);

  const renderItems = (rows: FindingRow[], severity: Severity, grayed = false) =>
    rows.map((r) => (
      <div
        key={r.key}
        onClick={r.locate ? () => onLocate(r.locate as string) : undefined}
        style={{
          border: '1px solid #eaecef',
          borderLeft: `3px solid ${grayed ? '#8c959f' : severity === 'ERROR' ? '#cf222e' : severity === 'WARN' ? '#bf8700' : '#0969da'}`,
          borderRadius: 6,
          padding: '8px 10px',
          margin: '6px 0',
          cursor: r.locate ? 'pointer' : 'default',
          fontSize: 12,
          opacity: grayed ? 0.75 : 1,
        }}
      >
        <div style={{ fontWeight: 600, marginBottom: 3, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>{r.title}</div>
        <div style={{ color: '#57606a', lineHeight: 1.5 }}>{r.reason}</div>
      </div>
    ));

  /** 组标题：名称 + 计数 + 严重度 Tag。 */
  const groupHeader = (label: string, count: number, sevTag: string, color: string) => (
    <span style={{ display: 'flex', alignItems: 'center', width: '100%' }}>
      <span style={{ fontWeight: 600 }}>{label}</span>
      <Typography.Text type="secondary" style={{ marginLeft: 6, fontWeight: 400 }}>({count})</Typography.Text>
      <Tag color={color} style={{ marginLeft: 'auto' }}>{sevTag}</Tag>
    </span>
  );

  const collapseItems = groups
    ? [
        {
          key: 'incoherences',
          label: groupHeader(t('group.incoherences'), groups.incoherences.length, t('sevTag.ERROR'), SEV_COLOR.ERROR),
          children: groups.incoherences.length ? renderItems(groups.incoherences, 'ERROR') : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('empty')} />,
        },
        {
          key: 'deadRules',
          label: groupHeader(t('group.deadRules'), groups.deadRules.length, t('sevTag.WARN'), SEV_COLOR.WARN),
          children: groups.deadRules.length ? renderItems(groups.deadRules, 'WARN') : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('empty')} />,
        },
        {
          key: 'conflicts',
          label: groupHeader(t('group.conflicts'), groups.conflicts.length, t('sevTag.WARN'), SEV_COLOR.WARN),
          children: groups.conflicts.length ? renderItems(groups.conflicts, 'WARN') : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('empty')} />,
        },
        {
          key: 'coverageGaps',
          label: groupHeader(t('group.coverageGaps'), groups.coverageGaps.length, t('sevTag.WARN'), SEV_COLOR.WARN),
          children: groups.coverageGaps.length ? renderItems(groups.coverageGaps, 'WARN') : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('empty')} />,
        },
        {
          key: 'overlaps',
          label: groupHeader(t('group.overlaps'), groups.overlaps.length, t('sevTag.INFO'), SEV_COLOR.INFO),
          children: groups.overlaps.length ? renderItems(groups.overlaps, 'INFO') : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('empty')} />,
        },
        {
          key: 'redundancies',
          label: groupHeader(t('group.redundancies'), groups.redundancies.length, t('sevTag.INFO'), SEV_COLOR.INFO),
          children: groups.redundancies.length ? renderItems(groups.redundancies, 'INFO') : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('empty')} />,
        },
        {
          key: 'unanalyzable',
          label: groupHeader(t('group.unanalyzable'), groups.unanalyzable.length, t('sevTag.SKIP'), 'default'),
          children: groups.unanalyzable.length ? (
            <>
              <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 4 }}>
                {t('unanalyzableNote')}
              </Typography.Text>
              {renderItems(groups.unanalyzable, 'INFO', true)}
            </>
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('empty')} />
          ),
        },
      ]
    : [];

  // 默认展开有内容的组
  const defaultActiveKeys = groups
    ? [
        groups.incoherences.length && 'incoherences',
        groups.deadRules.length && 'deadRules',
        groups.conflicts.length && 'conflicts',
        groups.coverageGaps.length && 'coverageGaps',
        groups.overlaps.length && 'overlaps',
        groups.redundancies.length && 'redundancies',
      ].filter(Boolean) as string[]
    : [];

  return (
    <Drawer
      title={
        <div>
          <div>{t('title')}</div>
          <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>scene: {sceneCode}</Typography.Text>
        </div>
      }
      open={open}
      onClose={onClose}
      width={440}
      extra={
        <Button icon={<ReloadOutlined />} size="small" onClick={onReanalyze} loading={loading}>
          {t('reanalyze')}
        </Button>
      }
    >
      {loading && !report ? (
        <Spin style={{ display: 'block', margin: '80px auto' }} />
      ) : !report ? (
        <Empty description={t('loadError')} />
      ) : (
        <>
          <Space size={[4, 8]} wrap style={{ marginBottom: 12 }}>
            <Tag color={SEV_COLOR.ERROR}>{t('group.incoherences')} {report.incoherences.length}</Tag>
            <Tag color={SEV_COLOR.WARN}>{t('group.deadRules')} {report.deadRules.length}</Tag>
            <Tag color={SEV_COLOR.WARN}>{t('group.conflicts')} {report.conflicts.length}</Tag>
            <Tag color={SEV_COLOR.WARN}>{t('group.coverageGaps')} {report.coverageGaps.length}</Tag>
            <Tag color={SEV_COLOR.INFO}>{t('group.overlaps')} {report.overlaps.length}</Tag>
            <Tag color={SEV_COLOR.INFO}>{t('group.redundancies')} {report.redundancies.length}</Tag>
            <Tag color="default">{t('group.unanalyzable')} {report.unanalyzableRules.length}</Tag>
          </Space>
          {totalFindings === 0 && report.unanalyzableRules.length === 0 ? (
            <Empty description={t('empty')} />
          ) : (
            <Collapse items={collapseItems} defaultActiveKey={defaultActiveKeys} />
          )}
        </>
      )}
    </Drawer>
  );
}
