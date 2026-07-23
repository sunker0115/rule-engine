import { useEffect, useState } from 'react';
import { Drawer, Spin, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { getRuleVersion } from '@/api/rule';
import JsonDiffViewer from '@/components/json-diff-viewer';
import type { RuleVersionContent } from '@/types';
import { bodyToCarriers } from '@/types';

interface Props {
  open: boolean;
  onClose: () => void;
  tenantId: number;
  ruleId: number;
  /** 待对比的历史版本 */
  versionId: number | null;
  /** 当前版本 id（diff 的右侧基准） */
  currentVersionId: number | null;
}

/** 提取参与 diff 的内容字段（去掉时间/发布人等元信息，只比配置内容） */
function contentForDiff(c: RuleVersionContent): Record<string, unknown> {
  const carriers = bodyToCarriers(c.body);
  return {
    kind: c.kind,
    conditionAst: carriers.conditionAst,
    decisionBindings: c.decisionBindings,
    preGates: c.preGates,
    triggerEventTypes: c.triggerEventTypes,
    script: carriers.script,
    flowGraph: carriers.flowGraph,
  };
}

/** 历史版本 ↔ 当前版本 的 JSON diff（复用审计页 JsonDiffViewer） */
export default function VersionDiffDrawer({ open, onClose, tenantId, ruleId, versionId, currentVersionId }: Props) {
  const { t } = useTranslation('rule');
  const [loading, setLoading] = useState(false);
  const [before, setBefore] = useState<RuleVersionContent | null>(null);
  const [after, setAfter] = useState<RuleVersionContent | null>(null);

  useEffect(() => {
    if (!open || !versionId || !currentVersionId || !tenantId) return;
    setLoading(true);
    Promise.all([
      getRuleVersion(tenantId, ruleId, versionId),
      getRuleVersion(tenantId, ruleId, currentVersionId),
    ])
      .then(([histRes, curRes]) => {
        setBefore(histRes ?? null);
        setAfter(curRes ?? null);
      })
      .finally(() => setLoading(false));
  }, [open, versionId, currentVersionId, ruleId, tenantId]);

  return (
    <Drawer
      title={before && after
        ? `${t('editor.versionDiff.title')}：v${before.version} → v${after.version}`
        : t('editor.versionDiff.title')}
      open={open}
      onClose={onClose}
      width={760}
    >
      {loading ? (
        <Spin style={{ display: 'block', margin: '80px auto' }} />
      ) : before && after ? (
        <>
          <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
            {t('editor.versionDiff.hint', { from: before.version, to: after.version })}
          </Typography.Text>
          <JsonDiffViewer before={contentForDiff(before)} after={contentForDiff(after)} />
        </>
      ) : (
        <Typography.Text type="secondary">—</Typography.Text>
      )}
    </Drawer>
  );
}
