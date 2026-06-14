import { useEffect, useState } from 'react';
import { Table, Space, Input, Select } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listScenes } from '@/api/scene';
import { listRules } from '@/api/rule';
import { getRuleColumns } from '@/config/columns/rule';
import { RULE_STATUS_OPTIONS } from '@/constants/enums';
import RuleDetailDrawer from '@/pages/rule-list/RuleDetailDrawer';
import type { RuleListItem } from '@/types';

interface RuleWithScene extends RuleListItem {
  sceneCode: string;
}

export default function RulesAll() {
  const { currentId } = useTenantStore();
  const { t } = useTranslation('rule');
  const [rules, setRules] = useState<RuleWithScene[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [detailId, setDetailId] = useState<number | null>(null);

  const load = async () => {
    if (!currentId) return;
    setLoading(true);
    try {
      const scenes = await listScenes(currentId);
      const sceneList = scenes.data ?? [];
      if (sceneList.length === 0) { setRules([]); return; }
      const params: Record<string, unknown> = {};
      if (statusFilter) params.status = statusFilter;
      const results = await Promise.all(
        sceneList.map(async (s) => {
          const page = await listRules(currentId, s.sceneCode, params);
          return (page.items ?? []).map((r) => ({ ...r, sceneCode: s.sceneCode }));
        }),
      );
      setRules(results.flat());
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [currentId, statusFilter]);

  const dataSource = keyword.trim()
    ? rules.filter((r) => r.name.includes(keyword) || r.code.includes(keyword))
    : rules;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>{t('title.list')}</h2>
      </div>
      <Space style={{ marginBottom: 16 }}>
        <Input
          prefix={<SearchOutlined />}
          placeholder="搜索名称或 Code"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          allowClear
          style={{ width: 240 }}
        />
        <Select
          placeholder="状态"
          value={statusFilter}
          onChange={setStatusFilter}
          allowClear
          options={[...RULE_STATUS_OPTIONS]}
          style={{ width: 130 }}
        />
      </Space>
      <Table
        columns={getRuleColumns(setDetailId)}
        dataSource={dataSource}
        rowKey="ruleDefinitionId"
        loading={loading}
      />
      <RuleDetailDrawer
        open={detailId !== null}
        ruleDefinitionId={detailId}
        onClose={() => setDetailId(null)}
      />
    </div>
  );
}
