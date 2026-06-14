import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Descriptions, Tabs, Button, Tag, Space, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getSession, getSessionTrace } from '@/api/evalSession';
import { colorOf, labelOf, SESSION_STATUS_OPTIONS } from '@/constants/enums';
import TraceTree from '@/components/trace-tree';
import type { EvalSessionItem, NodeTraceItem } from '@/types';

export default function EvalSessionDetail() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('eval');
  const tc = useTranslation('common').t;
  const [session, setSession] = useState<EvalSessionItem | null>(null);
  const [trace, setTrace] = useState<NodeTraceItem[]>([]);
  const [loading, setLoading] = useState(true);

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
  if (!session) return <div>会话不存在</div>;

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
      </Space>
      <Card title={t('session.detail.basicInfo')} style={{ marginBottom: 16 }}>
        <Descriptions column={3} size="small" bordered>
          <Descriptions.Item label={t('session.column.sessionId')}>{session.sessionId}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.eventId')}>{session.eventId}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.sceneCode')}>{session.sceneCode}</Descriptions.Item>
          <Descriptions.Item label="事件类型">{session.eventType || '-'}</Descriptions.Item>
          <Descriptions.Item label="主体ID">{session.subjectId || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.status')}>
            <Tag color={colorOf(SESSION_STATUS_OPTIONS, session.status)}>
              {labelOf(SESSION_STATUS_OPTIONS, session.status)}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="来源">{session.source || '-'}</Descriptions.Item>
          <Descriptions.Item label="模式">{session.mode || '-'}</Descriptions.Item>
          <Descriptions.Item label="决策结果">{session.finalDecision || '-'}</Descriptions.Item>
          {session.blockedBy && <Descriptions.Item label="拦截原因">{session.blockedBy}</Descriptions.Item>}
          {session.errorCode && <Descriptions.Item label="错误码"><Tag color="red">{session.errorCode}</Tag></Descriptions.Item>}
          {session.score != null && <Descriptions.Item label="评分">{session.score}</Descriptions.Item>}
          {session.category && <Descriptions.Item label="分类">{session.category}</Descriptions.Item>}
          <Descriptions.Item label="耗时(ms)">{session.evalDurationMs}</Descriptions.Item>
          <Descriptions.Item label="业务时间">{session.occurredAt || '-'}</Descriptions.Item>
          <Descriptions.Item label="开始时间">{session.startedAt || '-'}</Descriptions.Item>
          <Descriptions.Item label="结束时间">{session.finishedAt || '-'}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Tabs items={tabItems} />
    </div>
  );
}
