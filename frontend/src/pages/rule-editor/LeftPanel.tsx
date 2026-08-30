import { useState, useMemo } from 'react';
import { Descriptions, Button, Tag, Timeline, message, Popconfirm, Divider, Tooltip, Dropdown } from 'antd';
import { ExportOutlined } from '@ant-design/icons';
import { ThunderboltOutlined, EyeOutlined, DiffOutlined, RollbackOutlined, DeleteOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ROUTES } from '@/constants/routes';
import { ENDPOINTS } from '@/constants/api-endpoints';
import { useTenantStore } from '@/store/tenantStore';
import { useRuleStore } from '@/store/ruleStore';
import { editDraft, publishRule, disableRule, enableRule, newVersion, deleteDraftVersion, deleteRule } from '@/api/rule';
import apiClient from '@/api/client';
import { colorOf, getRuleStatusOptions, getVersionStatusOptions } from '@/constants/enums';
import { formatDateTime } from '@/utils/format';
import { useEditorShortcuts } from '@/hooks/useEditorShortcuts';
import RuleSessionsDrawer from './RuleSessionsDrawer';
import VersionContentDrawer from './VersionContentDrawer';
import VersionDiffDrawer from './VersionDiffDrawer';
import { summarize, worstSeverityForRule, isAnalyzableKind } from './analysisSummary';
import type { RuleDetail as RuleDetailType, RuleVersionItem, RuleSetAnalysisReport } from '@/types';
import { carriersToBody } from '@/types';

interface Props {
  ruleDetail: RuleDetailType;
  /** 打开试算抽屉；传入 version 则针对该历史版本，不传走默认最新版本 */
  onOpenDryRun: (version?: RuleVersionItem) => void;
  onUpdated: () => void;
  /** 轻量重算规则集分析（保存草稿后用——内容变了但状态/版本未变，无需全量 onUpdated）。 */
  onReanalyze?: () => void;
  /** 规则集分析报告（null 表示尚未拉取）。 */
  analysisReport?: RuleSetAnalysisReport | null;
  /** 点击摘要条打开规则集分析抽屉。 */
  onOpenAnalysis?: () => void;
}

export default function LeftPanel({ ruleDetail, onOpenDryRun, onUpdated, onReanalyze, analysisReport, onOpenAnalysis }: Props) {
  const { t } = useTranslation('rule');
  const tc = useTranslation('common').t;
  const ta = useTranslation('analysis').t;
  const ruleStatusOpts = useMemo(() => getRuleStatusOptions(t), [t]);
  const versionStatusOpts = useMemo(() => getVersionStatusOptions(t), [t]);
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  // 优先用规则自身的 tenantId（从详情带回），避免依赖全局未选时传 0 导致后端校验失败
  const tenantId = Number(ruleDetail.tenantId) || currentId || 0;
  const { ast, decisionBindings, preGates, triggerEventTypes, script, flowGraph, dirty, flowSceneRules, addFlowRuleRef, undo, redo } = useRuleStore();
  const [saving, setSaving] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [sessionsOpen, setSessionsOpen] = useState(false);
  const [viewVersionId, setViewVersionId] = useState<number | null>(null);
  const [diffVersionId, setDiffVersionId] = useState<number | null>(null);

  // 恢复此版本：克隆为新草稿（按当前世界重解析）→ 跳编辑器 review → 用户再发布
  const handleRestore = async (v: RuleVersionItem) => {
    try {
      await newVersion(tenantId, ruleDetail.ruleDefinitionId, v.ruleVersionId);
      message.success(t('editor.versionRestore.created', { version: v.version }));
      onUpdated();
    } catch { /* 重解析失败等由拦截器透出 */ }
  };

  const handleSaveDraft = async () => {
    setSaving(true);
    try {
      await editDraft(tenantId, ruleDetail.ruleDefinitionId, {
        body: carriersToBody(ruleDetail.kind, { conditionAst: ast, script, flowGraph }),
        decisionBindings,
        preGates,
        triggerEventTypes,
        kind: ruleDetail.kind,
        name: ruleDetail.name,
      });
      message.success(tc('message.saveSuccess'));
      // 草稿存盘后内容变了，规则集分析失效——轻量重算（不走全量 onUpdated，避免冗余请求）
      onReanalyze?.();
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
    // 出新版本：克隆当前 ACTIVE 版本为新草稿（fromVersionId=当前版本），而非建空白草稿丢失规则逻辑
    const fromVersionId = ruleDetail.currentVersionId
      ?? ruleDetail.versions?.find(v => v.status === 'ACTIVE')?.ruleVersionId;
    await newVersion(tenantId, ruleDetail.ruleDefinitionId, fromVersionId);
    message.success(tc('message.createSuccess'));
    onUpdated();
  };

  // 丢弃草稿：删除待发布 DRAFT，回到已发布/停用的基线版本（草稿三出口之一：发布/丢弃/继续编辑）
  const handleDiscardDraft = async () => {
    if (!draftVersion) return;
    await deleteDraftVersion(tenantId, ruleDetail.ruleDefinitionId, draftVersion.ruleVersionId);
    message.success(tc('message.deleteSuccess'));
    onUpdated();
  };

  // 删除整条规则：未发布过的新规则无基线版本可回退，丢弃即删规则本身（删后离开编辑器回列表）
  const handleDeleteRule = async () => {
    await deleteRule(tenantId, ruleDetail.ruleDefinitionId);
    message.success(tc('message.deleteSuccess'));
    navigate(ROUTES.RULES);
  };

  const handleExportRule = async (format: 'bundle' | 'snapshot' = 'bundle') => {
    if (!tenantId) return;
    setExporting(true);
    try {
      const res = await apiClient.get(ENDPOINTS.RULE_EXPORT, {
        params: { tenantId, ruleIds: String(ruleDetail.ruleDefinitionId), format },
        responseType: 'blob',
      });
      const blob = new Blob([res.data], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      const suffix = format === 'snapshot' ? '-snapshot' : '';
      a.download = `rule-${ruleDetail.code}${suffix}-${new Date().toISOString().slice(0, 10)}.json`;
      a.href = url;
      a.click();
      URL.revokeObjectURL(url);
      message.success(tc('message.exportSuccess'));
    } catch { message.error(tc('message.loadError')); }
    finally { setExporting(false); }
  };

  const isDraft = ruleDetail.status === 'DRAFT';
  const isPublished = ruleDetail.status === 'PUBLISHED';
  const isDisabled = ruleDetail.status === 'DISABLED';

  // 全局快捷键
  useEditorShortcuts({ canSave: isDraft && dirty, onSave: handleSaveDraft, onUndo: undo, onRedo: redo });

  // 待发布的即草稿版本，发布确认框展示其真实版本号
  const draftVersion = (ruleDetail.versions ?? []).find(v => v.status === 'DRAFT');
  const hasDraft = draftVersion !== undefined;
  // 有已发布/停用的基线版本可回退（丢弃草稿后回到它）；未发布的新规则无基线，丢弃即删规则
  const hasBaseVersion = isPublished || isDisabled;

  // 规则集分析仅对合取语义 kind 有意义（SCORECARD / EXPRESSION_SCRIPT 不可静态分析），不可分析时隐藏摘要条与按钮
  const analyzable = isAnalyzableKind(ruleDetail.kind);
  // 规则集分析：scene 级摘要 + 当前规则的最坏严重度（用于 badge）
  const summary = analyzable && analysisReport ? summarize(analysisReport) : null;
  const ruleSeverity = analysisReport ? worstSeverityForRule(analysisReport, ruleDetail.code) : null;
  const badgeColor: Record<string, string> = { ERROR: 'error', WARN: 'orange', INFO: 'blue', NA: 'default' };
  // badge 直接显严重度词，不锁某一类别名（WARN 可由死规则/冲突/缺口产出）
  const badgeLabel: Record<string, string> = {
    ERROR: ta('sevTag.ERROR'), WARN: ta('sevTag.WARN'), INFO: ta('sevTag.INFO'), NA: ta('sevTag.SKIP'),
  };

  return (
    <div style={{ padding: 16 }}>
      {summary && (
        <div
          onClick={onOpenAnalysis}
          title={ta('summaryBarTooltip')}
          onMouseEnter={(e) => { e.currentTarget.style.background = '#f0f3f6'; e.currentTarget.style.borderColor = '#d0d7de'; }}
          onMouseLeave={(e) => { e.currentTarget.style.background = '#fafbfc'; e.currentTarget.style.borderColor = '#eaecef'; }}
          style={{
            display: 'flex', gap: 10, alignItems: 'center',
            padding: '6px 10px', marginBottom: 12,
            background: '#fafbfc', border: '1px solid #eaecef', borderRadius: 6,
            cursor: 'pointer', fontSize: 12,
          }}
        >
          {summary.findingCount === 0 && summary.unanalyzable === 0 ? (
            <span style={{ color: '#1a7f37' }}>✓ {ta('allClear')}</span>
          ) : (
            <>
              <span style={{ color: '#cf222e' }}>⛔ {summary.error}</span>
              <span style={{ color: '#bf8700' }}>🟠 {summary.warn}</span>
              <span style={{ color: '#0969da' }}>🔵 {summary.info}</span>
              <span style={{ color: '#8c959f' }}>⚪ {summary.unanalyzable}</span>
            </>
          )}
          <span style={{ marginLeft: 'auto', color: '#8c959f' }}>{ta('summaryBarHint')} ›</span>
        </div>
      )}
      <h3>{t('editor.leftPanel.ruleInfo')}</h3>
      <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
        <Descriptions.Item label={t('column.code')}>
          {ruleDetail.code}
          {ruleSeverity && (
            <Tag color={badgeColor[ruleSeverity]} style={{ marginLeft: 6 }}>{badgeLabel[ruleSeverity]}</Tag>
          )}
        </Descriptions.Item>
        <Descriptions.Item label={tc('label.name')}>{ruleDetail.name}</Descriptions.Item>
        <Descriptions.Item label={t('column.kind')}><Tag>{ruleDetail.kind}</Tag></Descriptions.Item>
        {ruleDetail.kind === 'EXPRESSION_SCRIPT' && ruleDetail.body?.type === 'ScriptBody' && (
          <Descriptions.Item label={t('editor.leftPanel.executorLabel')}><Tag color="blue">{ruleDetail.body.script.lang}</Tag></Descriptions.Item>
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
        <Dropdown menu={{ items: [
          { key: 'bundle', label: t('action.exportBundle') },
          { key: 'snapshot', label: t('action.exportSnapshot') },
        ], onClick: ({ key }) => handleExportRule(key as 'bundle' | 'snapshot') }} trigger={['click']}>
          <Button block icon={<ExportOutlined />} loading={exporting} style={{ marginBottom: 8 }}>{t('action.export')}</Button>
        </Dropdown>

        <Divider plain style={{ margin: '12px 0', fontSize: 11, color: '#bbb' }}>{t('editor.leftPanel.dividerPublish')}</Divider>
        {hasDraft && (
          <>
            <Popconfirm title={t('version.publishConfirm').replace('{version}', String(draftVersion?.version ?? ''))} onConfirm={handlePublish}>
              <Button type="primary" block style={{ background: '#52c41a', borderColor: '#52c41a', marginBottom: 8 }}>
                {t('action.publish')}
              </Button>
            </Popconfirm>
            {/* 草稿的另一个出口：丢弃。有基线版本→只删草稿回基线；新规则无基线→删整条规则 */}
            {hasBaseVersion ? (
              <Popconfirm
                title={t('version.deleteDraftConfirm')}
                onConfirm={handleDiscardDraft}
                okText={tc('button.confirm')}
                cancelText={tc('button.cancel')}
                okButtonProps={{ danger: true }}
              >
                <Button block icon={<DeleteOutlined />} style={{ marginBottom: 8 }}>{t('action.deleteDraft')}</Button>
              </Popconfirm>
            ) : (
              <Popconfirm
                title={t('version.deleteRuleConfirm')}
                onConfirm={handleDeleteRule}
                okText={tc('button.confirm')}
                cancelText={tc('button.cancel')}
                okButtonProps={{ danger: true }}
              >
                <Button danger block icon={<DeleteOutlined />} style={{ marginBottom: 8 }}>{t('action.deleteRule')}</Button>
              </Popconfirm>
            )}
          </>
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

      {ruleDetail.kind === 'DECISION_FLOW' && (
        <div style={{ marginBottom: 12 }}>
          <h4>{t('editor.flow.leftPanel.sceneRules')}</h4>
          {flowSceneRules.length === 0 ? (
            <p style={{ fontSize: 11, color: '#8a95a1', margin: '4px 0' }}>{t('editor.flow.leftPanel.sceneRulesHint')}</p>
          ) : (
            <div style={{ maxHeight: 160, overflow: 'auto' }}>
              {flowSceneRules.map((r) => (
                <div key={r.code}
                  onClick={() => addFlowRuleRef(r.code)}
                  style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '4px 6px', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}
                  onMouseEnter={(e) => { e.currentTarget.style.background = '#f0f3f6'; }}
                  onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}>
                  <span style={{ width: 6, height: 6, borderRadius: 2, background: '#2f6bff', flex: 'none' }} />
                  <span>{r.name}</span>
                  {r.sceneCode && (
                    <Tag style={{ marginLeft: 'auto', fontSize: 10, lineHeight: '16px', padding: '0 4px' }} color="blue">{r.sceneCode}</Tag>
                  )}
                  <Tag style={{ marginLeft: r.sceneCode ? 0 : 'auto', fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{r.kind}</Tag>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      <h4>{t('editor.leftPanel.versionTimeline')}</h4>
      <Timeline
        items={(ruleDetail.versions ?? []).map(v => ({
          children: (
            <div style={{ display: 'flex', alignItems: 'center' }}>
              <Tag color={colorOf(versionStatusOpts, v.status as never)}>v{v.version}</Tag>
              <span style={{ fontSize: 12, color: '#999' }}>{formatDateTime(v.createdAt, 'YYYY-MM-DD')}</span>
              <div style={{ marginLeft: 'auto', display: 'flex' }}>
                <Tooltip title={t('editor.versionContent.title')}>
                  <Button type="text" size="small" icon={<EyeOutlined />} onClick={() => setViewVersionId(v.ruleVersionId)} />
                </Tooltip>
                {v.ruleVersionId !== ruleDetail.currentVersionId && (
                  <Tooltip title={t('editor.versionDiff.title')}>
                    <Button type="text" size="small" icon={<DiffOutlined />} onClick={() => setDiffVersionId(v.ruleVersionId)} />
                  </Tooltip>
                )}
                <Tooltip title={t('editor.leftPanel.dryRunVersion')}>
                  <Button type="text" size="small" icon={<ThunderboltOutlined />} onClick={() => onOpenDryRun(v)} />
                </Tooltip>
                {v.ruleVersionId !== ruleDetail.currentVersionId && (
                  <Tooltip title={hasDraft ? t('editor.versionRestore.blockedByDraft') : t('editor.versionRestore.title')}>
                    {hasDraft ? (
                      <Button type="text" size="small" icon={<RollbackOutlined />} disabled />
                    ) : (
                      <Popconfirm
                        title={t('editor.versionRestore.confirm', { version: v.version })}
                        onConfirm={() => handleRestore(v)}
                        okText={tc('button.confirm')}
                        cancelText={tc('button.cancel')}
                      >
                        <Button type="text" size="small" icon={<RollbackOutlined />} />
                      </Popconfirm>
                    )}
                  </Tooltip>
                )}
              </div>
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
      <VersionContentDrawer
        open={viewVersionId !== null}
        onClose={() => setViewVersionId(null)}
        tenantId={tenantId}
        ruleId={ruleDetail.ruleDefinitionId}
        versionId={viewVersionId}
      />
      <VersionDiffDrawer
        open={diffVersionId !== null}
        onClose={() => setDiffVersionId(null)}
        tenantId={tenantId}
        ruleId={ruleDetail.ruleDefinitionId}
        versionId={diffVersionId}
        currentVersionId={ruleDetail.currentVersionId ?? null}
      />
    </div>
  );
}
