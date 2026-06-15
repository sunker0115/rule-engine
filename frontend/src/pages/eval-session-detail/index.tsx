import { useEffect, useState, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Descriptions, Tabs, Button, Tag, Space, Spin, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getSession, getSessionTrace, replaySession } from '@/api/evalSession';
import { colorOf, labelOf, getSessionStatusOptions } from '@/constants/enums';
import { formatDateTime } from '@/utils/format';
import TraceTree from '@/components/trace-tree';
import type { EvalSessionItem, NodeTraceItem, EvalResult } from '@/types';

export default function EvalSessionDetail() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('eval');
  const tc = useTranslation('common').t;
  const sessionStatusOpts = useMemo(() => getSessionStatusOptions(t), [t]);
  const [session, setSession] = useState<EvalSessionItem | null>(null);
  const [trace, setTrace] = useState<NodeTraceItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [replayResult, setReplayResult] = useState<EvalResult | null>(null);
  const [replaying, setReplaying] = useState(false);

  const handleReplay = async () => {
    if (!currentId || !sessionId) return;
    setReplaying(true);
    try {
      const result = await replaySession(currentId, sessionId);
      setReplayResult(result);
    } catch {
      message.error(tc('message.loadError'));
    } finally {
      setReplaying(false);
    }
  };

  useEffect(() => {
    if (!currentId || !sessionId) return;
    (async () => {
      setLoading(true);
      try {
        const [s, tr] = await Promise.all([
          getSession(currentId, sessionId),
          getSessionTrace(currentId, sessionId),
        ]);
        setSession(s);
        setTrace(tr ?? []);
      } finally {
        setLoading(false);
      }
    })();
  }, [currentId, sessionId]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!session) return <div>{t('session.detail.notFound')}</div>;

  const tabItems = [
    {
      key: 'trace',
      label: t('session.detail.traceTree'),
      children: <TraceTree nodes={trace} />,
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button onClick={() => navigate(-1)}>{tc('button.back')}</Button>
        <h2 style={{ margin: 0 }}>{t('title.sessionDetail')} — {sessionId}</h2>
        <Button icon={<ReloadOutlined />} loading={replaying} onClick={handleReplay}>
          {t('replay.button')}
        </Button>
      </Space>
      <Card title={t('session.detail.basicInfo')} style={{ marginBottom: 16 }}>
        <Descriptions column={3} size="small" bordered>
          <Descriptions.Item label={t('session.column.sessionId')}>{session.sessionId}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.eventId')}>{session.eventId}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.sceneCode')}>{session.sceneCode}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.eventType')}>{session.eventType || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.subjectId')}>{session.subjectId || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.status')}>
            <Tag color={colorOf(sessionStatusOpts, session.status)}>
              {labelOf(sessionStatusOpts, session.status)}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('session.column.source')}>{session.source || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.mode')}>{session.mode || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.finalDecision')}>{session.finalDecision || '-'}</Descriptions.Item>
          {session.blockedBy && <Descriptions.Item label={t('session.detail.blockedBy')}>{session.blockedBy}</Descriptions.Item>}
          {session.errorCode && <Descriptions.Item label={t('session.detail.errorCode')}><Tag color="red">{session.errorCode}</Tag></Descriptions.Item>}
          {session.score != null && <Descriptions.Item label={t('session.detail.score')}>{session.score}</Descriptions.Item>}
          {session.category && <Descriptions.Item label={t('session.detail.category')}>{session.category}</Descriptions.Item>}
          <Descriptions.Item label={t('session.column.evalDuration')}>{session.evalDurationMs}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.occurredAt')}>{formatDateTime(session.occurredAt)}</Descriptions.Item>
          <Descriptions.Item label={t('session.detail.startedAt')}>{formatDateTime(session.startedAt)}</Descriptions.Item>
          <Descriptions.Item label={t('session.detail.finishedAt')}>{formatDateTime(session.finishedAt)}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Tabs items={tabItems} />

      {replayResult && (
        <Card
          title={t('replay.resultTitle')}
          style={{ marginTop: 16 }}
          extra={<Tag color="blue">{t('replay.consistencyHint')}</Tag>}
        >
          <Descriptions column={2} size="small" bordered style={{ marginBottom: 16 }}>
            <Descriptions.Item label={t('replay.ruleHit')}>
              {replayResult.ruleHit
                ? <Tag color="green">{t('dryRun.result.hit')}</Tag>
                : <Tag color="orange">{t('dryRun.result.miss')}</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label={t('dryRun.result.finalDecision')}>
              {replayResult.finalDecision ? replayResult.finalDecision.code : '-'}
            </Descriptions.Item>
            {replayResult.errorCode && (
              <Descriptions.Item label={t('session.detail.errorCode')}>
                <Tag color="red">{replayResult.errorCode}</Tag>
              </Descriptions.Item>
            )}
          </Descriptions>
          <TraceTree nodes={replayResult.nodeTrace} />
        </Card>
      )}
    </div>
  );
}
