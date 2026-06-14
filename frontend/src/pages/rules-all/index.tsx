import { useEffect, useState } from 'react';
import { Table, Tag } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listScenes } from '@/api/scene';
import { listRules } from '@/api/rule';
import { colorOf, RULE_STATUS_OPTIONS } from '@/constants/enums';
import { ROUTES, route } from '@/constants/routes';
import type { RuleListItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

interface RuleWithScene extends RuleListItem {
  _sceneCode: string;
}

export default function RulesAll() {
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('rule');
  const [rules, setRules] = useState<RuleWithScene[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    if (!currentId) return;
    setLoading(true);
    try {
      const scenes = await listScenes(currentId);
      const sceneList = scenes.data ?? [];
      if (sceneList.length === 0) {
        setRules([]);
        return;
      }
      const results = await Promise.all(
        sceneList.map(async (s) => {
          const page = await listRules(currentId, s.sceneCode);
          return (page.items ?? []).map((r) => ({ ...r, _sceneCode: s.sceneCode }));
        }),
      );
      setRules(results.flat());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [currentId]);

  const columns: ColumnsType<RuleWithScene> = [
    { title: 'Code', dataIndex: 'code', key: 'code' },
    { title: t('column.name'), dataIndex: 'name', key: 'name' },
    { title: 'Scene', dataIndex: '_sceneCode', key: '_sceneCode' },
    {
      title: t('column.status'), dataIndex: 'status', key: 'status',
      render: (v: string) => <Tag color={colorOf(RULE_STATUS_OPTIONS, v as never)}>{v}</Tag>,
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>{t('title.list')}</h2>
      </div>
      <Table
        columns={columns}
        dataSource={rules}
        rowKey="ruleDefinitionId"
        loading={loading}
        onRow={(r) => ({
          onClick: () => navigate(route(ROUTES.RULE_EDITOR, { sceneCode: r._sceneCode, ruleId: r.ruleDefinitionId })),
          style: { cursor: 'pointer' },
        })}
      />
    </div>
  );
}
