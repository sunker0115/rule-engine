import { useState } from 'react';
import { Tabs, Card, Radio, Input, Button, Upload, Tag, Space, message } from 'antd';
import { DownloadOutlined, InboxOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { ENDPOINTS } from '@/constants/api-endpoints';
import apiClient from '@/api/client';
import type { RuleImportResult } from '@/types';
import type { UploadFile } from 'antd';

const { Dragger } = Upload;

type ExportScope = 'all' | 'byScene' | 'byRuleIds';

export default function ImportExport() {
  const { currentId } = useTenantStore();
  const { t } = useTranslation('importExport');
  const [exportScope, setExportScope] = useState<ExportScope>('all');
  const [exportScene, setExportScene] = useState('');
  const [exportRuleIds, setExportRuleIds] = useState('');
  const [exporting, setExporting] = useState(false);
  // 导入状态
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [importPreview, setImportPreview] = useState<Record<string, unknown> | null>(null);
  const [importResult, setImportResult] = useState<RuleImportResult | null>(null);
  const [importing, setImporting] = useState(false);

  const handleExport = async () => {
    if (!currentId) return;
    setExporting(true);
    try {
      const params: Record<string, unknown> = { tenantId: currentId };
      if (exportScope === 'byScene') params.sceneCode = exportScene;
      if (exportScope === 'byRuleIds') params.ruleIds = exportRuleIds.split(',').map((s) => s.trim()).filter(Boolean).join(',');

      const res = await apiClient.get(ENDPOINTS.RULE_EXPORT, { params, responseType: 'blob' });
      const blob = new Blob([res.data], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `rule-export-${currentId}-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
      message.success('下载完成');
    } finally {
      setExporting(false);
    }
  };

  const handleImportPreview = (file: File) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const json = JSON.parse(e.target?.result as string);
        setImportPreview(json);
        setImportResult(null);
      } catch {
        message.error(t('import.error.parseError'));
      }
    };
    reader.readAsText(file);
    return false;
  };

  const handleImport = async () => {
    if (!currentId || !fileList[0]) return;
    setImporting(true);
    try {
      const formData = new FormData();
      formData.append('file', fileList[0].originFileObj as Blob);
      const res = await apiClient.post<RuleImportResult>(`${ENDPOINTS.RULE_IMPORT}?tenantId=${currentId}`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      setImportResult(res.data);
      message.success('导入完成');
    } finally {
      setImporting(false);
    }
  };

  const countItems = (key: string): number => {
    if (!importPreview) return 0;
    if (key === 'rules') {
      const r = importPreview[key];
      return Array.isArray(r) ? r.length : 0;
    }
    const v = importPreview[key];
    return Array.isArray(v) ? v.length : 0;
  };

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>{t('title.page')}</h2>
      <Tabs
        items={[
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
                    <Input
                      placeholder="Scene Code"
                      value={exportScene}
                      onChange={(e) => setExportScene(e.target.value)}
                      style={{ width: 200 }}
                    />
                  </div>
                )}
                {exportScope === 'byRuleIds' && (
                  <div style={{ marginBottom: 16 }}>
                    <Input
                      placeholder="1,2,3"
                      value={exportRuleIds}
                      onChange={(e) => setExportRuleIds(e.target.value)}
                      style={{ width: 300 }}
                    />
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
                <Dragger
                  fileList={fileList}
                  accept=".json"
                  maxCount={1}
                  beforeUpload={handleImportPreview}
                  onRemove={() => { setFileList([]); setImportPreview(null); setImportResult(null); }}
                  onChange={(info) => setFileList(info.fileList)}
                >
                  <p className="ant-upload-drag-icon"><InboxOutlined /></p>
                  <p className="ant-upload-text">{t('import.upload')}</p>
                  <p className="ant-upload-hint">{t('import.uploadHint')}</p>
                </Dragger>

                {importPreview && !importResult && (
                  <Card title={t('import.preview')} size="small" style={{ marginTop: 16 }}>
                    <p>{t('import.previewTitle')}</p>
                    <div style={{ display: 'flex', gap: 16 }}>
                      <Tag>{t('import.newDraft')}: {countItems('rules')}</Tag>
                      {countItems('scenes') > 0 && <Tag>{t('import.existing')}: {countItems('scenes')}</Tag>}
                      {countItems('metrics') > 0 && <Tag>{t('import.skip')}: {countItems('metrics')}</Tag>}
                    </div>
                    <Button type="primary" loading={importing} onClick={handleImport} style={{ marginTop: 12 }}>
                      {t('import.execute')}
                    </Button>
                  </Card>
                )}

                {importResult && (
                  <Card title={t('import.result.title')} size="small" style={{ marginTop: 16 }}>
                    <Space direction="vertical">
                      <span>{t('import.result.rulesImported')}: {importResult.rules?.length ?? 0}</span>
                      <span>{t('import.result.scenesCreated')}: {importResult.scenesCreated?.join(', ') || '-'}</span>
                      <span>{t('import.result.scenesSkipped')}: {importResult.scenesSkippedExisting?.join(', ') || '-'}</span>
                      <span>{t('import.result.metricsCreated')}: {importResult.metricsCreated?.join(', ') || '-'}</span>
                      <span>{t('import.result.metricsSkipped')}: {importResult.metricsSkippedExisting?.join(', ') || '-'}</span>
                      <span>{t('import.result.metricsReview')}: {importResult.metricsRequiringReview?.join(', ') || '-'}</span>
                      <span>{t('import.result.decisionsCreated')}: {importResult.decisionsCreated?.join(', ') || '-'}</span>
                      <span>{t('import.result.decisionsSkipped')}: {importResult.decisionsSkippedExisting?.join(', ') || '-'}</span>
                    </Space>
                  </Card>
                )}
              </Card>
            ),
          },
        ]}
      />
    </div>
  );
}
