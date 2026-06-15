import { useState } from 'react';
import { Alert, Button, Input, Space, Tag, Typography, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { testConnector } from '@/api/connector';
import type { FetchTrace } from '@/types';

interface Props {
  /** 连接器编码；新建未保存态为空 */
  connectorCode: string;
  /** 编辑态（已存在 connector）才允许测试 */
  isEdit: boolean;
}

/** 把 JSON 文本解析为对象；空文本返回 undefined；解析失败抛错 */
function parseJsonObject(text: string): Record<string, unknown> | undefined {
  const trimmed = text.trim();
  if (!trimmed) return undefined;
  const parsed = JSON.parse(trimmed);
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('not an object');
  }
  return parsed as Record<string, unknown>;
}

/** 已知 errorCode 集合——用于查 i18n 含义说明 */
type KnownErrorCode = keyof ReturnType<typeof useErrorMeaning>;
function useErrorMeaning() {
  const { t } = useTranslation('connector');
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

/** 可滚动代码块——展示渲染后请求 / 原始响应等多行文本 */
function CodeBlock({ text }: { text: string }) {
  return (
    <pre
      style={{
        margin: 0,
        padding: 12,
        maxHeight: 240,
        overflow: 'auto',
        background: '#f5f5f5',
        borderRadius: 4,
        fontFamily: 'monospace',
        fontSize: 12,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-all',
      }}
    >
      {text}
    </pre>
  );
}

/** 连接器内联自助测试面板：填样例 → 调 :test → 分阶段展示取数链路 trace */
export default function TestPanel({ connectorCode, isEdit }: Props) {
  const { t } = useTranslation('connector');
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
      const res = await testConnector(connectorCode, currentId, {
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

  if (!isEdit) {
    return <Alert type="info" showIcon message={t('test.saveFirst')} />;
  }

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

      {trace && <TraceResult trace={trace} errorMeaning={errorMeaning} />}
    </Space>
  );
}

/** 分阶段展示 FetchTrace：渲染请求 / 原始响应 / 成功判定 / 映射值 / 错误码（失败高亮） */
function TraceResult({
  trace,
  errorMeaning,
}: {
  trace: FetchTrace;
  errorMeaning: Record<string, string>;
}) {
  const { t } = useTranslation('connector');
  const hasError = !!trace.errorCode;
  const meaning = trace.errorCode ? errorMeaning[trace.errorCode as KnownErrorCode] : undefined;
  const mapped =
    trace.mappedValue === undefined || trace.mappedValue === null
      ? t('test.empty')
      : typeof trace.mappedValue === 'string'
        ? trace.mappedValue
        : JSON.stringify(trace.mappedValue);

  return (
    <Space direction="vertical" style={{ width: '100%', marginTop: 8 }} size="middle">
      <Typography.Title level={5} style={{ margin: 0 }}>
        {t('test.result')}
      </Typography.Title>

      {/* 总体成败横幅——失败 error 色 + errorCode 含义；成功 success 色 */}
      {hasError ? (
        <Alert
          type="error"
          showIcon
          message={
            <Space>
              {t('test.failure')}
              <Tag color="error">{trace.errorCode}</Tag>
            </Space>
          }
          description={meaning}
        />
      ) : (
        <Alert type="success" showIcon message={t('test.success')} />
      )}

      {trace.renderedRequest && (
        <div>
          <Typography.Text strong>{t('test.renderedRequest')}</Typography.Text>
          <CodeBlock text={trace.renderedRequest} />
        </div>
      )}

      {trace.rawResponse && (
        <div>
          <Typography.Text strong>{t('test.rawResponse')}</Typography.Text>
          <CodeBlock text={trace.rawResponse} />
        </div>
      )}

      <div>
        <Typography.Text strong style={{ marginRight: 8 }}>
          {t('test.successMatched')}
        </Typography.Text>
        <Tag color={trace.successMatched ? 'success' : 'default'}>
          {trace.successMatched ? t('test.matched') : t('test.notMatched')}
        </Tag>
      </div>

      <div>
        <Typography.Text strong style={{ marginRight: 8 }}>
          {t('test.mappedValue')}
        </Typography.Text>
        <Typography.Text code>{mapped}</Typography.Text>
      </div>
    </Space>
  );
}
