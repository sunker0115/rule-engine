import { useMemo, useState } from 'react';
import { Alert, Button, Input, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { testConnector } from '@/api/connector';
import FetchTraceView from '@/components/fetch-trace-view';
import type { ConnectorDescriptor, FetchTrace } from '@/types';

interface Props {
  /** 连接器编码；新建未保存态为空 */
  connectorCode: string;
  /** 编辑态（已存在 connector）才允许测试 */
  isEdit: boolean;
  /** 当前描述符——用于提取 {vars.x} / {payload.x} 占位符，自动生成输入项 */
  descriptor: ConnectorDescriptor;
  /** 所属租户 */
  tenantId: number;
}

/** 已知 errorCode → 含义说明（connector 命名空间七码） */
function useErrorMeaning(): Record<string, string> {
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

/** 从描述符各模板字段提取 {前缀.xxx} 占位符名（去重有序） */
function extractPlaceholders(d: ConnectorDescriptor, prefix: 'vars' | 'payload'): string[] {
  const targets = [
    d.request?.pathTemplate ?? '',
    d.request?.bodyTemplate ?? '',
    ...(d.request?.query ?? []).map((p) => p.valueTemplate),
    ...(d.request?.headers ?? []).map((p) => p.valueTemplate),
  ];
  const re = new RegExp(`\\{${prefix}\\.([a-zA-Z_][\\w.]*)\\}`, 'g');
  const seen = new Set<string>();
  targets.forEach((tmpl) => { [...tmpl.matchAll(re)].forEach((m) => seen.add(m[1])); });
  return [...seen];
}

/** 是否引用了主体 id 占位符 {subjectId}（裸命名空间，对应后端 sampleSubjectId） */
function usesSubject(d: ConnectorDescriptor): boolean {
  const all = [
    d.request?.pathTemplate ?? '',
    d.request?.bodyTemplate ?? '',
    ...(d.request?.query ?? []).map((p) => p.valueTemplate),
    ...(d.request?.headers ?? []).map((p) => p.valueTemplate),
  ].join(' ');
  // 后端命名空间：{subjectId} 裸 = 主体 id；{subject.x} = 主体属性（:test 暂只支持主体 id）
  return /\{subjectId\}/.test(all) || /\{subject\./.test(all);
}

/** 连接器内联自助测试面板：按描述符占位符生成键值输入 → 调 :test → 分阶段展示取数链路 trace */
export default function TestPanel({ connectorCode, isEdit, descriptor, tenantId }: Props) {
  const { t } = useTranslation('connector');
  const errorMeaning = useErrorMeaning();

  const varsKeys = useMemo(() => extractPlaceholders(descriptor, 'vars'), [descriptor]);
  const payloadKeys = useMemo(() => extractPlaceholders(descriptor, 'payload'), [descriptor]);
  const needSubject = useMemo(() => usesSubject(descriptor), [descriptor]);

  const [varsVals, setVarsVals] = useState<Record<string, string>>({});
  const [payloadVals, setPayloadVals] = useState<Record<string, string>>({});
  const [subjectId, setSubjectId] = useState('');
  const [running, setRunning] = useState(false);
  const [trace, setTrace] = useState<FetchTrace | null>(null);

  const buildObj = (keys: string[], vals: Record<string, string>): Record<string, unknown> | undefined => {
    const entries = keys.filter((k) => vals[k] !== undefined && vals[k] !== '').map((k) => [k, vals[k]]);
    return entries.length ? Object.fromEntries(entries) : undefined;
  };

  const handleRun = async () => {
    if (!tenantId) return;
    setRunning(true);
    setTrace(null);
    try {
      const res = await testConnector(connectorCode, tenantId, {
        sampleVars: buildObj(varsKeys, varsVals),
        samplePayload: buildObj(payloadKeys, payloadVals),
        sampleSubjectId: subjectId.trim() || undefined,
      });
      if (res) setTrace(res);
    } catch {
      // 错误已由 axios 响应拦截器统一 message.error 透出
    } finally {
      setRunning(false);
    }
  };

  if (!isEdit) {
    return <Alert type="info" showIcon message={t('test.saveFirst')} />;
  }

  const noInputs = varsKeys.length === 0 && payloadKeys.length === 0 && !needSubject;

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      {noInputs && <Typography.Text type="secondary">{t('test.noInputs')}</Typography.Text>}

      {needSubject && (
        <div>
          <Typography.Text strong>{t('test.sampleSubjectId')}</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block' }}>{'{subjectId}'}</Typography.Text>
          <Input value={subjectId} onChange={(e) => setSubjectId(e.target.value)} />
        </div>
      )}

      {varsKeys.length > 0 && (
        <div>
          <Typography.Text strong>{t('test.sampleVars')}</Typography.Text>
          {varsKeys.map((k) => (
            <div key={k} style={{ marginTop: 6 }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{`{vars.${k}}`}</Typography.Text>
              <Input value={varsVals[k] ?? ''} placeholder={k}
                onChange={(e) => setVarsVals((v) => ({ ...v, [k]: e.target.value }))} />
            </div>
          ))}
        </div>
      )}

      {payloadKeys.length > 0 && (
        <div>
          <Typography.Text strong>{t('test.samplePayload')}</Typography.Text>
          {payloadKeys.map((k) => (
            <div key={k} style={{ marginTop: 6 }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{`{payload.${k}}`}</Typography.Text>
              <Input value={payloadVals[k] ?? ''} placeholder={k}
                onChange={(e) => setPayloadVals((v) => ({ ...v, [k]: e.target.value }))} />
            </div>
          ))}
        </div>
      )}

      <Button type="primary" loading={running} onClick={handleRun}>
        {t('test.run')}
      </Button>

      {trace && <FetchTraceView trace={trace} errorMeaning={errorMeaning} t={t} />}
    </Space>
  );
}
