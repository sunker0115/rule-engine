import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Descriptions, Tabs, Button, Tag, Space, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getSession, getSessionTrace } from '@/api/evalSession';
import { colorOf, labelOf, SESSION_STATUS_OPTIONS, EVENT_SOURCE_OPTIONS } from '@/constants/enums';
import TraceTree from '@/components/trace-tree';
import type { EvalSessionDetail, NodeTraceItem } from '@/types';

export default function EvalSessionDetail() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('eval');
  const tc = useTranslation('common').t;
  const [session, setSession] = useState<EvalSessionDetail | null>(null);
  const [trace, setTrace] = useState<NodeTraceItem[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    if (!currentId || !sessionId) return;
    setLoading(true);
    try {
      const [s, tr] = await Promise.all([
        getSession(currentId, Number(sessionId)),
        getSessionTrace(currentId, Number(sessionId)),
      ]);
      setSession(s.data);
      setTrace(tr.data ?? []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [currentId, sessionId]);

  if (!session) return <Spin spinning={loading} />;

  const items = [
    {
      key: 'trace',
      label: t('session.detail.traceTree'),
      children: <TraceTree nodes={trace} />,
    },
    {
      key: 'hit',
      label: t('session.detail.hitRules'),
      children: <div style={{ color: '#999' }}>暂无数据</div>,
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button onClick={() => navigate(-1)}>{tc('button.back')}</Button>
        <h2 style={{ margin: 0 }}>{t('title.sessionDetail')} — {sessionId}</h2>
      </Space>
      <Card title={t('session.detail.basicInfo')} style={{ marginBottom: 16 }}>
        <Descriptions column={2} size="small" bordered>
          <Descriptions.Item label={t('session.column.sessionId')}>{session.sessionId}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.eventId')}>{session.eventId}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.sceneCode')}>{session.sceneCode}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.eventType')}>{session.eventType}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.subjectId')}>{session.subjectId}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.status')}>
            <Tag color={colorOf(SESSION_STATUS_OPTIONS, session.status)}>
              {labelOf(SESSION_STATUS_OPTIONS, session.status)}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('session.column.finalDecision')}>{session.finalDecision || '-'}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.source')}>
            <Tag color={colorOf(EVENT_SOURCE_OPTIONS, session.source)}>{session.source}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('session.column.mode')}>{session.mode}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.evalDuration')}>{session.evalDurationMs} ms</Descriptions.Item>
          <Descriptions.Item label={t('session.column.candidateRuleCount')}>{session.candidateRuleCount}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.hitRuleCount')}>{session.hitRuleCount}</Descriptions.Item>
          <Descriptions.Item label={t('session.column.occurredAt')}>{session.occurredAt}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Tabs items={items} />
    </div>
  );
}
