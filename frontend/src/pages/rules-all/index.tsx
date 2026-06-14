import { useEffect, useState, useMemo } from 'react';
import { Table, Space, Input, Select, DatePicker } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listRules } from '@/api/rule';
import { getRuleColumns } from '@/config/columns/rule';
import { RULE_STATUS_OPTIONS } from '@/constants/enums';
import RuleDetailDrawer from '@/pages/rule-list/RuleDetailDrawer';
import dayjs from 'dayjs';
import type { RuleListItem } from '@/types';

const { RangePicker } = DatePicker;

export default function RulesAll() {
  const { currentId, activeList } = useTenantStore();
  const { t } = useTranslation('rule');
  const [rules, setRules] = useState<RuleListItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null] | null>(null);
  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);
  const [detailId, setDetailId] = useState<number | null>(null);
  const tenantId = tenantFilter ?? currentId ?? 0;

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try {
      const params: Record<string, unknown> = {};
      if (statusFilter) params.status = statusFilter;
      if (dateRange?.[0]) params.from = dateRange[0].format('YYYY-MM-DD');
      if (dateRange?.[1]) params.to = dateRange[1].format('YYYY-MM-DD');
      const data = await listRules(tenantId, undefined, params);
      setRules(data.items ?? []);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [tenantId, statusFilter, dateRange]);

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
        <Select
          placeholder="租户"
          value={tenantFilter}
          onChange={setTenantFilter}
          allowClear
          options={activeList.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))}
          style={{ width: 180 }}
        />
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
        <RangePicker
          value={dateRange as [dayjs.Dayjs, dayjs.Dayjs] | null}
          onChange={(dates) => setDateRange(dates as [dayjs.Dayjs | null, dayjs.Dayjs | null] | null)}
          placeholder={['发布时间起', '发布时间止']}
          style={{ width: 260 }}
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
