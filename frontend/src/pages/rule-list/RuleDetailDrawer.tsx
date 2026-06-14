import { useEffect, useState } from 'react';
import { Drawer, Descriptions, Tag, Spin, Timeline, Tabs, Empty } from 'antd';
import { useTenantStore } from '@/store/tenantStore';
import { getRule } from '@/api/rule';
import { colorOf, RULE_STATUS_OPTIONS, VERSION_STATUS_OPTIONS } from '@/constants/enums';
import type { RuleDetail as RuleDetailType } from '@/types';

interface Props {
  open: boolean;
  ruleDefinitionId: number | null;
  onClose: () => void;
}

export default function RuleDetailDrawer({ open, ruleDefinitionId, onClose }: Props) {
  const { currentId } = useTenantStore();
  const [detail, setDetail] = useState<RuleDetailType | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (open && ruleDefinitionId && currentId) {
      setLoading(true);
      getRule(currentId, ruleDefinitionId)
        .then((res) => setDetail(res.data ?? null))
        .finally(() => setLoading(false));
    }
    return () => setDetail(null);
  }, [open, ruleDefinitionId, currentId]);

  if (loading) return <Drawer title="规则详情" open={open} onClose={onClose} width={520}><Spin /></Drawer>;
  if (!detail) return <Drawer title="规则详情" open={open} onClose={onClose} width={520}><Empty /></Drawer>;

  // 找当前生效版本
  const activeVersion = detail.versions?.find((v) => v.status === 'ACTIVE');
  const draftVersion = detail.versions?.find((v) => v.status === 'DRAFT');

  return (
    <Drawer title={`${detail.code}`} open={open} onClose={onClose} width={520}>
      <Tabs
        items={[
          {
            key: 'info',
            label: '基本信息',
            children: (
              <Descriptions column={1} size="small" bordered>
                <Descriptions.Item label="名称">{detail.name}</Descriptions.Item>
                <Descriptions.Item label="类型"><Tag>{detail.kind}</Tag></Descriptions.Item>
                <Descriptions.Item label="场景">{detail.sceneCode}</Descriptions.Item>
                <Descriptions.Item label="状态">
                  <Tag color={colorOf(RULE_STATUS_OPTIONS, detail.status)}>{detail.status}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="触发事件">
                  {(detail.triggerEventTypes ?? []).join(', ') || '-'}
                </Descriptions.Item>
                <Descriptions.Item label="Decision">
                  {(detail.decisionBindings ?? []).map((b) => b.decisionCode).join(', ') || '-'}
                </Descriptions.Item>
                <Descriptions.Item label="Pre-Gate">
                  {detail.preGates?.[0]?.gateType === 'ROLLOUT'
                    ? `ROLLOUT ${detail.preGates[0].params?.percentage ?? '?'}%`
                    : '-'}
                </Descriptions.Item>
                {activeVersion && (
                  <Descriptions.Item label="生效版本">v{activeVersion.version}</Descriptions.Item>
                )}
                {draftVersion && (
                  <Descriptions.Item label="在途草稿">
                    <Tag color="blue">v{draftVersion.version}</Tag>
                  </Descriptions.Item>
                )}
              </Descriptions>
            ),
          },
          {
            key: 'versions',
            label: `版本历史 (${detail.versions?.length ?? 0})`,
            children: detail.versions && detail.versions.length > 0 ? (
              <Timeline
                items={detail.versions.map((v) => ({
                  color: v.status === 'ACTIVE' ? 'green' : v.status === 'DRAFT' ? 'blue' : 'gray',
                  children: (
                    <div>
                      <Tag color={colorOf(VERSION_STATUS_OPTIONS, v.status)}>v{v.version}</Tag>
                      <div style={{ fontSize: 12, color: '#999', marginTop: 4 }}>
                        {v.createdAt?.slice(0, 16)}
                        {v.publishedBy && <span> · {v.publishedBy}</span>}
                      </div>
                    </div>
                  ),
                }))}
              />
            ) : <Empty description="暂无版本" />,
          },
        ]}
      />
    </Drawer>
  );
}
