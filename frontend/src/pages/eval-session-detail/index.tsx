import { useEffect, useState } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import { Card, Descriptions, Tabs, Button, Tag, Space, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getSessionTrace } from '@/api/evalSession';
import { colorOf, labelOf, SESSION_STATUS_OPTIONS, EVENT_SOURCE_OPTIONS } from '@/constants/enums';
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

  // session 基础信息来自列表页路由 state（后端无 GET one session 端点）
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

  const items = [
    {
      key: 'trace',
      label: t('session.detail.traceTree'),
      children: traceLoading ? <Spin /> : <TraceTree nodes={trace} />,
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
      {session && (
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
            <Descriptions.Item label={t('session.column.occurredAt')}>{session.startedAt}</Descriptions.Item>
          </Descriptions>
        </Card>
      )}
      <Tabs items={items} />
    </div>
  );
}
