import { useEffect, useState } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import { Card, Descriptions, Tabs, Button, Tag, Space, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getSessionTrace } from '@/api/evalSession';
import { colorOf, labelOf, SESSION_STATUS_OPTIONS } from '@/constants/enums';
import TraceTree from '@/components/trace-tree';
import type { EvalSessionItem, NodeTraceItem } from '@/types';

interface LocationState {
  session?: EvalSessionItem;
}

export default function EvalSessionDetail() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('eval');
  const tc = useTranslation('common').t;

  const state = location.state as LocationState | null;
  const [session] = useState<EvalSessionItem | null>(state?.session ?? null);
  const [trace, setTrace] = useState<NodeTraceItem[]>([]);
  const [traceLoading, setTraceLoading] = useState(false);

  useEffect(() => {
    if (!currentId || !sessionId) return;
    (async () => {
      setTraceLoading(true);
      try {
        const tr = await getSessionTrace(currentId, Number(sessionId));
        setTrace(tr ?? []);
      } finally {
        setTraceLoading(false);
      }
    })();
  }, [currentId, sessionId]);

  const tabItems = [
    {
      key: 'trace',
      label: t('session.detail.traceTree'),
      children: traceLoading ? <Spin /> : <TraceTree nodes={trace} />,
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button onClick={() => navigate(-1)}>{tc('button.back')}</Button>
        <h2 style={{ margin: 0 }}>{t('title.sessionDetail')} — {sessionId}</h2>
      </Space>
      {session && (
        <Card title={t('session.detail.basicInfo')} style={{ marginBottom: 16 }}>
          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label={t('session.column.sessionId')}>{session.sessionId}</Descriptions.Item>
            <Descriptions.Item label={t('session.column.eventId')}>{session.eventId}</Descriptions.Item>
            <Descriptions.Item label={t('session.column.sceneCode')}>{session.sceneCode}</Descriptions.Item>
            <Descriptions.Item label={t('session.column.status')}>
              <Tag color={colorOf(SESSION_STATUS_OPTIONS, session.status)}>
                {labelOf(SESSION_STATUS_OPTIONS, session.status)}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="决策结果">{session.finalDecision || '-'}</Descriptions.Item>
            <Descriptions.Item label="耗时(ms)">{session.evalDurationMs != null ? session.evalDurationMs : '-'}</Descriptions.Item>
            <Descriptions.Item label={t('session.column.occurredAt')}>{session.startedAt || '-'}</Descriptions.Item>
            <Descriptions.Item label="结束时间">{session.finishedAt || '-'}</Descriptions.Item>
          </Descriptions>
        </Card>
      )}
      <Tabs items={tabItems} />
    </div>
  );
}
