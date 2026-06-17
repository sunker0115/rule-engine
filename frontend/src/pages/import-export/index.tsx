import { useState } from 'react';
import { Tabs, Card, Radio, Input, Button, Upload, Tag, Space, message, Modal, Table, Alert } from 'antd';
import { DownloadOutlined, InboxOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { ENDPOINTS } from '@/constants/api-endpoints';
import apiClient from '@/api/client';
import type { ImportDiffReport, ImportPolicy } from '@/types';
import type { UploadFile } from 'antd';

const { Dragger } = Upload;

type ExportScope = 'all' | 'byScene' | 'byRuleIds';

export default function ImportExport() {
  const { currentId } = useTenantStore();
  const { t } = useTranslation('importExport');

  // export state
  const [exportScope, setExportScope] = useState<ExportScope>('all');
  const [exportScene, setExportScene] = useState('');
  const [exportRuleIds, setExportRuleIds] = useState('');
  const [exporting, setExporting] = useState(false);

  // import state
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [importPolicy, setImportPolicy] = useState<ImportPolicy>('SKIP');
  const [dryRunReport, setDryRunReport] = useState<ImportDiffReport | null>(null);
  const [applyResult, setApplyResult] = useState<ImportDiffReport | null>(null);
  const [dryRunning, setDryRunning] = useState(false);
  const [applying, setApplying] = useState(false);
  const [diffModalOpen, setDiffModalOpen] = useState(false);

  // ---- export -------------------------------------------------------

  const handleExport = async () => {
    if (!currentId) return;
    setExporting(true);
    try {
      const params: Record<string, unknown> = { tenantId: currentId };
      if (exportScope === 'byScene') params.sceneCode = exportScene;
      if (exportScope === 'byRuleIds')
        params.ruleIds = exportRuleIds.split(',').map((s) => s.trim()).filter(Boolean).join(',');

      const res = await apiClient.get(ENDPOINTS.RULE_EXPORT, { params, responseType: 'blob' });
      const blob = new Blob([res.data], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `rule-bundle-${currentId}-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
      message.success(t('export.downloadComplete'));
    } finally {
      setExporting(false);
    }
  };

  // ---- import: dry-run → diff modal → apply -------------------------

  const getFile = () => fileList[0]?.originFileObj as Blob | undefined;

  const handleDryRun = async () => {
    if (!currentId || !getFile()) return;
    setDryRunning(true);
    try {
      const form = new FormData();
      form.append('file', getFile()!);
      const res = await apiClient.post(
        `${ENDPOINTS.RULE_IMPORT}?tenantId=${currentId}&policy=${importPolicy}&dryRun=true`,
        form, { headers: { 'Content-Type': 'multipart/form-data' } });
      setDryRunReport(res.data.data as ImportDiffReport);
      setDiffModalOpen(true);
    } catch {
      message.error(t('import.error.dryRunFailed'));
    } finally {
      setDryRunning(false);
    }
  };

  const handleApply = async () => {
    if (!currentId || !getFile()) return;
    setApplying(true);
    setDiffModalOpen(false);
    try {
      const form = new FormData();
      form.append('file', getFile()!);
      const res = await apiClient.post(
        `${ENDPOINTS.RULE_IMPORT}?tenantId=${currentId}&policy=${importPolicy}&dryRun=false`,
        form, { headers: { 'Content-Type': 'multipart/form-data' } });
      setApplyResult(res.data.data as ImportDiffReport);
      message.success(t('import.importComplete'));
    } catch (err: unknown) {
      const e = err as { response?: { data?: { errorCode?: string } } };
      if (e?.response?.data?.errorCode === 'IMPORT_CONFLICT') {
        message.error(t('import.error.conflicts'));
      } else {
        message.error(t('import.error.applyFailed'));
      }
    } finally {
      setApplying(false);
    }
  };

  // ---- diff modal content --------------------------------------------

  const diffColumns = [
    { title: t('import.diff.ruleCode'), dataIndex: 'ruleCode', key: 'ruleCode', width: 180 },
    { title: t('import.diff.sceneCode'), dataIndex: 'sceneCode', key: 'sceneCode', width: 140 },
    { title: t('import.diff.reason'), dataIndex: 'reason', key: 'reason' },
  ];

  const conflictColumns = [
    { title: t('import.diff.ruleCode'), dataIndex: 'ruleCode', key: 'ruleCode', width: 180 },
    { title: t('import.diff.sceneCode'), dataIndex: 'sceneCode', key: 'sceneCode', width: 140 },
    { title: t('import.diff.conflictType'), dataIndex: 'conflictType', key: 'conflictType', width: 140 },
    { title: t('import.diff.detail'), dataIndex: 'detail', key: 'detail' },
  ];

  const hasConflicts = (dryRunReport?.conflicts?.length ?? 0) > 0;

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>{t('title.page')}</h2>

      {/* ---- diff 预览 modal ---- */}
      <Modal
        title={t('import.diff.title')}
        open={diffModalOpen}
        onCancel={() => setDiffModalOpen(false)}
        width={800}
        footer={[
          <Button key="cancel" onClick={() => setDiffModalOpen(false)}>{t('import.diff.cancel')}</Button>,
          <Button
            key="apply"
            type="primary"
            danger={hasConflicts}
            loading={applying}
            disabled={importPolicy === 'ABORT' && hasConflicts}
            onClick={handleApply}
          >
            {t('import.diff.confirm')}
          </Button>,
        ]}
      >
        {dryRunReport && (
          <Space direction="vertical" style={{ width: '100%' }}>
            {hasConflicts && (
              <Alert
                type={importPolicy === 'ABORT' ? 'error' : 'warning'}
                showIcon
                icon={<ExclamationCircleOutlined />}
                message={importPolicy === 'ABORT' ? t('import.diff.abortHint') : t('import.diff.conflictHint')}
              />
            )}
            {dryRunReport.willCreate.length > 0 && (
              <Card size="small" title={<Tag color="green">{t('import.diff.willCreate')} ({dryRunReport.willCreate.length})</Tag>}>
                <Table size="small" dataSource={dryRunReport.willCreate} columns={diffColumns} rowKey="ruleCode" pagination={false} />
              </Card>
            )}
            {dryRunReport.willOverwrite.length > 0 && (
              <Card size="small" title={<Tag color="blue">{t('import.diff.willOverwrite')} ({dryRunReport.willOverwrite.length})</Tag>}>
                <Table size="small" dataSource={dryRunReport.willOverwrite} columns={diffColumns} rowKey="ruleCode" pagination={false} />
              </Card>
            )}
            {dryRunReport.skipped.length > 0 && (
              <Card size="small" title={<Tag>{t('import.diff.skipped')} ({dryRunReport.skipped.length})</Tag>}>
                <Table size="small" dataSource={dryRunReport.skipped} columns={diffColumns} rowKey="ruleCode" pagination={false} />
              </Card>
            )}
            {dryRunReport.conflicts.length > 0 && (
              <Card size="small" title={<Tag color="red">{t('import.diff.conflicts')} ({dryRunReport.conflicts.length})</Tag>}>
                <Table size="small" dataSource={dryRunReport.conflicts} columns={conflictColumns} rowKey="ruleCode" pagination={false} />
              </Card>
            )}
          </Space>
        )}
      </Modal>

      <Tabs items={[
        {
          key: 'export',
          label: t('tab.export'),
          children: (
            <Card>
              <div style={{ marginBottom: 16 }}>
                <div style={{ marginBottom: 8 }}>{t('export.scope')}</div>
                <Radio.Group value={exportScope} onChange={(e) => setExportScope(e.target.value)}>
                  <Radio.Button value="all">{t('export.all')}</Radio.Button>
                  <Radio.Button value="byScene">{t('export.byScene')}</Radio.Button>
                  <Radio.Button value="byRuleIds">{t('export.byRuleIds')}</Radio.Button>
                </Radio.Group>
              </div>
              {exportScope === 'byScene' && (
                <div style={{ marginBottom: 16 }}>
                  <Input placeholder={t('export.sceneCodePlaceholder')} value={exportScene}
                    onChange={(e) => setExportScene(e.target.value)} style={{ width: 200 }} />
                </div>
              )}
              {exportScope === 'byRuleIds' && (
                <div style={{ marginBottom: 16 }}>
                  <Input placeholder={t('export.ruleIdsPlaceholder')} value={exportRuleIds}
                    onChange={(e) => setExportRuleIds(e.target.value)} style={{ width: 300 }} />
                </div>
              )}
              <Button type="primary" icon={<DownloadOutlined />} loading={exporting} onClick={handleExport}>
                {t('export.download')}
              </Button>
            </Card>
          ),
        },
        {
          key: 'import',
          label: t('tab.import'),
          children: (
            <Card>
              {/* 冲突策略选择 */}
              <div style={{ marginBottom: 16 }}>
                <div style={{ marginBottom: 8, fontWeight: 500 }}>{t('import.policy.label')}</div>
                <Radio.Group value={importPolicy} onChange={(e) => setImportPolicy(e.target.value)}>
                  <Radio.Button value="SKIP">{t('import.policy.skip')}</Radio.Button>
                  <Radio.Button value="OVERWRITE">{t('import.policy.overwrite')}</Radio.Button>
                  <Radio.Button value="ABORT">{t('import.policy.abort')}</Radio.Button>
                </Radio.Group>
                <div style={{ color: '#888', fontSize: 12, marginTop: 4 }}>
                  {t(`import.policy.hint.${importPolicy.toLowerCase()}`)}
                </div>
              </div>

              <Dragger
                fileList={fileList}
                accept=".json"
                maxCount={1}
                beforeUpload={() => false}
                onRemove={() => { setFileList([]); setDryRunReport(null); setApplyResult(null); }}
                onChange={(info) => setFileList(info.fileList)}
              >
                <p className="ant-upload-drag-icon"><InboxOutlined /></p>
                <p className="ant-upload-text">{t('import.upload')}</p>
                <p className="ant-upload-hint">{t('import.uploadHint')}</p>
              </Dragger>

              {fileList.length > 0 && !applyResult && (
                <Button
                  type="primary"
                  loading={dryRunning}
                  onClick={handleDryRun}
                  style={{ marginTop: 12 }}
                >
                  {t('import.previewAndImport')}
                </Button>
              )}

              {applyResult && (
                <Card title={t('import.result.title')} size="small" style={{ marginTop: 16 }}>
                  <Space direction="vertical">
                    <span>{t('import.result.willCreate')}: {applyResult.willCreate.length}</span>
                    <span>{t('import.result.willOverwrite')}: {applyResult.willOverwrite.length}</span>
                    <span>{t('import.result.skipped')}: {applyResult.skipped.length}</span>
                    <span>{t('import.result.scenesCreated')}: {applyResult.scenesCreated}</span>
                    <span>{t('import.result.metricsCreated')}: {applyResult.metricsCreated}</span>
                    <span>{t('import.result.decisionsCreated')}: {applyResult.decisionsCreated}</span>
                  </Space>
                </Card>
              )}
            </Card>
          ),
        },
      ]} />
    </div>
  );
}
