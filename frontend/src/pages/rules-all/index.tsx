import { useEffect, useState, useMemo } from 'react';
import { Table, Space, Input, Select } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listRules } from '@/api/rule';
import { getRuleColumns } from '@/config/columns/rule';
import { RULE_STATUS_OPTIONS } from '@/constants/enums';
import RuleDetailDrawer from '@/pages/rule-list/RuleDetailDrawer';
import type { RuleListItem } from '@/types';

export default function RulesAll() {
  const { currentId } = useTenantStore();
  const { t } = useTranslation('rule');
  const [rules, setRules] = useState<RuleListItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [detailId, setDetailId] = useState<number | null>(null);

  const load = async () => {
    if (!currentId) return;
    setLoading(true);
    try {
      const params: Record<string, unknown> = {};
      if (statusFilter) params.status = statusFilter;
      // 不传 sceneCode，查全租户规则
      const data = await listRules(currentId, undefined, params);
      setRules(data.items ?? []);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [currentId, statusFilter]);

  const dataSource = useMemo(() => {
    if (!keyword.trim()) return rules;
    const kw = keyword.toLowerCase();
    return rules.filter((r) => r.name.toLowerCase().includes(kw) || r.code.toLowerCase().includes(kw));
  }, [rules, keyword]);

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
