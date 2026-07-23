import { useEffect, useState } from 'react';
import { Drawer, Descriptions, Spin, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { getRuleVersion } from '@/api/rule';
import type { RuleVersionContent } from '@/types';
import { bodyToCarriers } from '@/types';

interface Props {
  open: boolean;
  onClose: () => void;
  tenantId: number;
  ruleId: number;
  versionId: number | null;
}

/** 只读查看某历史版本的完整内容（conditionAst / bindings / preGates / script 以 JSON 呈现） */
export default function VersionContentDrawer({ open, onClose, tenantId, ruleId, versionId }: Props) {
  const { t } = useTranslation('rule');
  const [loading, setLoading] = useState(false);
  const [content, setContent] = useState<RuleVersionContent | null>(null);

  useEffect(() => {
    if (!open || !versionId || !tenantId) return;
    setLoading(true);
    getRuleVersion(tenantId, ruleId, versionId)
      .then((res) => setContent(res ?? null))
      .finally(() => setLoading(false));
  }, [open, versionId, ruleId, tenantId]);

  const json = (v: unknown) => (
    <pre style={{ margin: 0, fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
      {v == null ? '—' : JSON.stringify(v, null, 2)}
    </pre>
  );

  return (
    <Drawer
      title={content ? `${t('editor.versionContent.title')} v${content.version}` : t('editor.versionContent.title')}
      open={open}
      onClose={onClose}
      width={640}
    >
      {loading ? (
        <Spin style={{ display: 'block', margin: '80px auto' }} />
      ) : content ? (
        <Descriptions column={1} bordered size="small">
          <Descriptions.Item label={t('editor.versionContent.version')}>
            v{content.version} <Tag>{content.status}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('form.kind')}>{content.kind}</Descriptions.Item>
          <Descriptions.Item label={t('editor.versionContent.triggerEventTypes')}>
            {(content.triggerEventTypes ?? []).join(', ') || '—'}
          </Descriptions.Item>
          <Descriptions.Item label={t('editor.versionContent.conditionAst')}>{json(bodyToCarriers(content.body).conditionAst)}</Descriptions.Item>
          <Descriptions.Item label={t('editor.versionContent.decisionBindings')}>{json(content.decisionBindings)}</Descriptions.Item>
          <Descriptions.Item label={t('editor.versionContent.preGates')}>{json(content.preGates)}</Descriptions.Item>
          {bodyToCarriers(content.body).script && (
            <Descriptions.Item label={t('editor.versionContent.script')}>{json(bodyToCarriers(content.body).script)}</Descriptions.Item>
          )}
          {bodyToCarriers(content.body).flowGraph && (
            <Descriptions.Item label={t('editor.versionContent.flowGraph')}>{json(bodyToCarriers(content.body).flowGraph)}</Descriptions.Item>
          )}
          <Descriptions.Item label={t('editor.versionContent.publishedBy')}>
            {content.publishedBy || '—'}
          </Descriptions.Item>
        </Descriptions>
      ) : (
        <Typography.Text type="secondary">—</Typography.Text>
      )}
    </Drawer>
  );
}
