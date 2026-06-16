import { useState, useMemo } from 'react';
import { Descriptions, Button, Tag, Timeline, message, Popconfirm, Divider, Tooltip } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { useRuleStore } from '@/store/ruleStore';
import { editDraft, publishRule, disableRule, enableRule, newVersion } from '@/api/rule';
import { colorOf, getRuleStatusOptions, getVersionStatusOptions } from '@/constants/enums';
import { formatDateTime } from '@/utils/format';
import RuleSessionsDrawer from './RuleSessionsDrawer';
import type { RuleDetail as RuleDetailType, RuleVersionItem } from '@/types';

interface Props {
  ruleDetail: RuleDetailType;
  /** 打开试算抽屉；传入 version 则针对该历史版本，不传走默认最新版本 */
  onOpenDryRun: (version?: RuleVersionItem) => void;
  onUpdated: () => void;
}

export default function LeftPanel({ ruleDetail, onOpenDryRun, onUpdated }: Props) {
  const { t } = useTranslation('rule');
  const tc = useTranslation('common').t;
  const ruleStatusOpts = useMemo(() => getRuleStatusOptions(t), [t]);
  const versionStatusOpts = useMemo(() => getVersionStatusOptions(t), [t]);
  const { currentId } = useTenantStore();
  // 优先用规则自身的 tenantId（从详情带回），避免依赖全局未选时传 0 导致后端校验失败
  const tenantId = Number(ruleDetail.tenantId) || currentId || 0;
  const { ast, decisionBindings, preGates, triggerEventTypes, script, dirty } = useRuleStore();
  const [saving, setSaving] = useState(false);
  const [sessionsOpen, setSessionsOpen] = useState(false);

  const handleSaveDraft = async () => {
    setSaving(true);
    try {
      await editDraft(tenantId, ruleDetail.ruleDefinitionId, {
        conditionAst: ast,
        decisionBindings,
        preGates,
        triggerEventTypes,
        kind: ruleDetail.kind,
        name: ruleDetail.name,
        script,
      });
      message.success(tc('message.saveSuccess'));
    } finally { setSaving(false); }
  };

  const handlePublish = async () => {
    await publishRule(tenantId, ruleDetail.ruleDefinitionId);
    message.success(tc('message.updateSuccess'));
    onUpdated();
  };

  const handleDisable = async () => {
    await disableRule(tenantId, ruleDetail.ruleDefinitionId);
    message.success(tc('message.updateSuccess'));
    onUpdated();
  };

  const handleEnable = async () => {
    await enableRule(tenantId, ruleDetail.ruleDefinitionId);
    message.success(tc('message.updateSuccess'));
    onUpdated();
  };

  const handleNewVersion = async () => {
    await newVersion(tenantId, ruleDetail.ruleDefinitionId);
    message.success(tc('message.createSuccess'));
    onUpdated();
  };

  const isDraft = ruleDetail.status === 'DRAFT';
  const isPublished = ruleDetail.status === 'PUBLISHED';
  const isDisabled = ruleDetail.status === 'DISABLED';
  // 待发布的即草稿版本，发布确认框展示其真实版本号
  const draftVersion = (ruleDetail.versions ?? []).find(v => v.status === 'DRAFT');
  const hasDraft = draftVersion !== undefined;

  return (
    <div style={{ padding: 16 }}>
      <h3>{t('editor.leftPanel.ruleInfo')}</h3>
      <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
        <Descriptions.Item label={t('column.code')}>{ruleDetail.code}</Descriptions.Item>
        <Descriptions.Item label={tc('label.name')}>{ruleDetail.name}</Descriptions.Item>
        <Descriptions.Item label={t('column.kind')}><Tag>{ruleDetail.kind}</Tag></Descriptions.Item>
        {ruleDetail.kind === 'EXPRESSION_SCRIPT' && ruleDetail.script && (
          <Descriptions.Item label={t('editor.leftPanel.executorLabel')}><Tag color="blue">{ruleDetail.script.lang}</Tag></Descriptions.Item>
        )}
        <Descriptions.Item label={t('column.status')}>
          <Tag color={colorOf(ruleStatusOpts, ruleDetail.status as never)}>{ruleDetail.status}</Tag>
        </Descriptions.Item>
      </Descriptions>

      <div style={{ marginBottom: 16 }}>
        {/* 编辑与测试 */}
        {isDraft && (
          <Button type="primary" block onClick={handleSaveDraft} loading={saving} disabled={!dirty} style={{ marginBottom: 8 }}>
            {t('action.saveDraft')}
          </Button>
        )}
        {(isPublished || isDisabled) && !hasDraft && (
          <Button block onClick={handleNewVersion} style={{ marginBottom: 8 }}>{t('action.newVersion')}</Button>
        )}
        <Button block onClick={() => onOpenDryRun()} style={{ marginBottom: 8 }}>{t('action.dryRun')}</Button>
        <Button block onClick={() => setSessionsOpen(true)} style={{ marginBottom: 8 }}>{t('action.sessions')}</Button>

        <Divider plain style={{ margin: '12px 0', fontSize: 11, color: '#bbb' }}>{t('editor.leftPanel.dividerPublish')}</Divider>
        {hasDraft && (
          <Popconfirm title={t('version.publishConfirm').replace('{version}', String(draftVersion?.version ?? ''))} onConfirm={handlePublish}>
            <Button type="primary" block style={{ background: '#52c41a', borderColor: '#52c41a', marginBottom: 8 }}>
              {t('action.publish')}
            </Button>
          </Popconfirm>
        )}

        {(isPublished || isDisabled) && (
          <Divider plain style={{ margin: '12px 0', fontSize: 11, color: '#bbb' }}>{t('editor.leftPanel.dividerManage')}</Divider>
        )}
        {isPublished && (
          <Popconfirm title={t('version.disableConfirm')} onConfirm={handleDisable}>
            <Button danger block style={{ marginBottom: 8 }}>{t('action.disable')}</Button>
          </Popconfirm>
        )}
        {isDisabled && (
          <Button block onClick={handleEnable}>{t('action.enable')}</Button>
        )}
      </div>

      <h4>{t('editor.leftPanel.versionTimeline')}</h4>
      <Timeline
        items={(ruleDetail.versions ?? []).map(v => ({
          children: (
            <div style={{ display: 'flex', alignItems: 'center' }}>
              <Tag color={colorOf(versionStatusOpts, v.status as never)}>v{v.version}</Tag>
              <span style={{ fontSize: 12, color: '#999' }}>{formatDateTime(v.createdAt, 'YYYY-MM-DD')}</span>
              <Tooltip title={t('editor.leftPanel.dryRunVersion')}>
                <Button
                  type="text"
                  size="small"
                  icon={<ThunderboltOutlined />}
                  onClick={() => onOpenDryRun(v)}
                  style={{ marginLeft: 'auto' }}
                />
              </Tooltip>
            </div>
          ),
        }))}
      />

      <RuleSessionsDrawer
        open={sessionsOpen}
        onClose={() => setSessionsOpen(false)}
        tenantId={tenantId}
        ruleDefinitionId={ruleDetail.ruleDefinitionId}
      />
    </div>
  );
}
