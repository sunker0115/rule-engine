import { useState } from 'react';
import { Button, Input, Space, Typography, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { testMetric } from '@/api/metric';
import FetchTraceView, { parseJsonObject } from '@/components/fetch-trace-view';
import type { FetchTrace } from '@/types';

interface Props {
  /** 指标编码 */
  metricCode: string;
}

/** 已知 errorCode → 含义说明（metric 命名空间七码） */
function useErrorMeaning(): Record<string, string> {
  const { t } = useTranslation('metric');
  return {
    PARSE_ERROR: t('test.errorMeaning.PARSE_ERROR'),
    UPSTREAM_ERROR: t('test.errorMeaning.UPSTREAM_ERROR'),
    UNAUTHORIZED: t('test.errorMeaning.UNAUTHORIZED'),
    TIMEOUT: t('test.errorMeaning.TIMEOUT'),
    NOT_FOUND: t('test.errorMeaning.NOT_FOUND'),
    MAPPING_ERROR: t('test.errorMeaning.MAPPING_ERROR'),
    TYPE_MISMATCH: t('test.errorMeaning.TYPE_MISMATCH'),
  };
}

/** 指标自助试算面板：填样例 → 调 :test 实打实取数一次 → 分阶段展示取数链路 trace */
export default function TestPanel({ metricCode }: Props) {
  const { t } = useTranslation('metric');
  const { currentId } = useTenantStore();
  const errorMeaning = useErrorMeaning();

  const [varsText, setVarsText] = useState('');
  const [payloadText, setPayloadText] = useState('');
  const [subjectId, setSubjectId] = useState('');
  const [running, setRunning] = useState(false);
  const [trace, setTrace] = useState<FetchTrace | null>(null);

  const handleRun = async () => {
    if (!currentId) return;
    let sampleVars: Record<string, unknown> | undefined;
    let samplePayload: Record<string, unknown> | undefined;
    try {
      sampleVars = parseJsonObject(varsText);
      samplePayload = parseJsonObject(payloadText);
    } catch {
      message.error(t('test.invalidJson'));
      return;
    }
    setRunning(true);
    setTrace(null);
    try {
      const res = await testMetric(metricCode, currentId, {
        sampleVars,
        samplePayload,
        sampleSubjectId: subjectId.trim() || undefined,
      });
      if (res.data) setTrace(res.data);
    } catch {
      // 错误已由 axios 响应拦截器统一 message.error 透出
    } finally {
      setRunning(false);
    }
  };

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <div>
        <Typography.Text>{t('test.sampleVars')}</Typography.Text>
        <Input.TextArea
          rows={3}
          style={{ fontFamily: 'monospace' }}
          value={varsText}
          placeholder={t('test.sampleVarsHint')}
          onChange={(e) => setVarsText(e.target.value)}
        />
      </div>
      <div>
        <Typography.Text>{t('test.samplePayload')}</Typography.Text>
        <Input.TextArea
          rows={3}
          style={{ fontFamily: 'monospace' }}
          value={payloadText}
          placeholder={t('test.samplePayloadHint')}
          onChange={(e) => setPayloadText(e.target.value)}
        />
      </div>
      <div>
        <Typography.Text>{t('test.sampleSubjectId')}</Typography.Text>
        <Input value={subjectId} onChange={(e) => setSubjectId(e.target.value)} />
      </div>
      <Button type="primary" loading={running} onClick={handleRun}>
        {t('test.run')}
      </Button>

      {trace && <FetchTraceView trace={trace} errorMeaning={errorMeaning} t={t} />}
    </Space>
  );
}
