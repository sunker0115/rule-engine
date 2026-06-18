import { Alert, Space, Tag, Typography } from 'antd';
import type { TFunction } from 'i18next';
import type { FetchTrace } from '@/types';

/** 七码错误含义映射（errorCode → 人话说明），由调用方按命名空间注入 */
export type ErrorMeaning = Record<string, string>;

/** 把 JSON 文本解析为对象；空文本返回 undefined；解析失败抛错 */
export function parseJsonObject(text: string): Record<string, unknown> | undefined {
  const trimmed = text.trim();
  if (!trimmed) return undefined;
  const parsed = JSON.parse(trimmed);
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('not an object');
  }
  return parsed as Record<string, unknown>;
}

/** 可滚动代码块——展示渲染后请求 / 绑定后 SQL / 原始响应等多行文本 */
export function CodeBlock({ text }: { text: string }) {
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

interface Props {
  /** :test 端点返回的取数链路 trace */
  trace: FetchTrace;
  /** errorCode → 含义说明（connector / metric 各自命名空间注入） */
  errorMeaning: ErrorMeaning;
  /** test.* 命名空间的翻译函数（connector 或 metric） */
  t: TFunction;
}

/**
 * 分阶段展示 FetchTrace：渲染请求 / 绑定 SQL / 原始响应 / 成功判定 / 映射值 / 错误码（失败高亮）。
 *
 * connector 与 metric 共用：HTTP 类填 renderedRequest，SQL 类填 boundSql，二者择一展示。
 */
export default function FetchTraceView({ trace, errorMeaning, t }: Props) {
  const hasError = !!trace.errorCode;
  const meaning = trace.errorCode ? errorMeaning[trace.errorCode] : undefined;
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

      {/* HTTP 类取数：渲染后请求 */}
      {trace.renderedRequest && (
        <div>
          <Typography.Text strong>{t('test.renderedRequest')}</Typography.Text>
          <CodeBlock text={trace.renderedRequest} />
        </div>
      )}

      {/* SQL 类取数：绑定参数后的 SQL */}
      {trace.boundSql && (
        <div>
          <Typography.Text strong>{t('test.boundSql')}</Typography.Text>
          <CodeBlock text={trace.boundSql} />
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
