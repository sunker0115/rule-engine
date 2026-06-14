import { useState } from 'react';
import { Descriptions, Button, Space, Tag, Timeline, message, Popconfirm } from 'antd';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import { editDraft, publishRule, disableRule, newVersion } from '@/api/rule';
import { colorOf, RULE_STATUS_OPTIONS, VERSION_STATUS_OPTIONS } from '@/constants/enums';
import type { RuleDetail as RuleDetailType } from '@/types';

interface Props { ruleDetail: RuleDetailType; }

export default function LeftPanel({ ruleDetail }: Props) {
  const { t } = useTranslation('rule');
  const tc = useTranslation('common').t;
  const { ast, decisionBindings, preGates, triggerEventTypes, dirty } = useRuleStore();
  const [saving, setSaving] = useState(false);

  const handleSaveDraft = async () => {
    setSaving(true);
    try {
      await editDraft(ruleDetail.ruleDefinitionId, {
        conditionAst: ast,
        decisionBindings,
        preGates,
        triggerEventTypes,
        kind: ruleDetail.kind,
        name: ruleDetail.name,
      });
      message.success(tc('message.saveSuccess'));
    } finally { setSaving(false); }
  };

  const handlePublish = async () => {
    await publishRule(ruleDetail.ruleDefinitionId);
    message.success(tc('message.updateSuccess'));
  };

  const handleDisable = async () => {
    await disableRule(ruleDetail.ruleDefinitionId);
    message.success(tc('message.updateSuccess'));
  };

  const handleNewVersion = async () => {
    await newVersion(ruleDetail.ruleDefinitionId);
    message.success(tc('message.createSuccess'));
  };

  const isDraft = ruleDetail.status === 'DRAFT';
  const isPublished = ruleDetail.status === 'PUBLISHED';
  const isDisabled = ruleDetail.status === 'DISABLED';
  const hasDraft = (ruleDetail.versions ?? []).some(v => v.status === 'DRAFT');

  return (
    <div style={{ padding: 16 }}>
      <h3>{t('editor.leftPanel.ruleInfo')}</h3>
      <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
        <Descriptions.Item label="Code">{ruleDetail.code}</Descriptions.Item>
        <Descriptions.Item label={tc('label.name')}>{ruleDetail.name}</Descriptions.Item>
        <Descriptions.Item label={t('column.kind')}><Tag>{ruleDetail.kind}</Tag></Descriptions.Item>
        <Descriptions.Item label={t('column.status')}>
          <Tag color={colorOf(RULE_STATUS_OPTIONS, ruleDetail.status as never)}>{ruleDetail.status}</Tag>
        </Descriptions.Item>
      </Descriptions>

      <Space direction="vertical" style={{ width: '100%', marginBottom: 16 }}>
        {isDraft && (
          <Button type="primary" block onClick={handleSaveDraft} loading={saving} disabled={!dirty}>
            {t('action.saveDraft')}
          </Button>
        )}
        {hasDraft && (
          <Popconfirm title={t('version.publishConfirm').replace('{version}', '?')} onConfirm={handlePublish}>
            <Button type="primary" block style={{ background: '#52c41a', borderColor: '#52c41a' }}>
              {t('action.publish')}
            </Button>
          </Popconfirm>
        )}
        {(isPublished || isDisabled) && !hasDraft && (
          <Button block onClick={handleNewVersion}>{t('action.newVersion')}</Button>
        )}
        {isPublished && (
          <Popconfirm title={t('version.disableConfirm')} onConfirm={handleDisable}>
            <Button danger block>{t('action.disable')}</Button>
          </Popconfirm>
        )}
        {isDisabled && (
          <Button block onClick={handleDisable}>{t('action.enable')}</Button>
        )}
      </Space>

      <h4>{t('editor.leftPanel.versionTimeline')}</h4>
      <Timeline
        items={(ruleDetail.versions ?? []).map(v => ({
          children: (
            <div>
              <Tag color={colorOf(VERSION_STATUS_OPTIONS, v.status as never)}>v{v.version}</Tag>
              <span style={{ fontSize: 12, color: '#999' }}>{v.createdAt?.slice(0, 10)}</span>
            </div>
          ),
        }))}
      />
    </div>
  );
}
