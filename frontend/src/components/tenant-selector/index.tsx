import { useEffect } from 'react';
import { Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';

export default function TenantSelector() {
  const { t } = useTranslation('common');
  const { current, activeList, setCurrent, searchTenants } = useTenantStore();

  useEffect(() => {
    searchTenants(undefined, 'ACTIVE').then((tenants) => {
      useTenantStore.setState({ activeList: tenants });
    });
  }, [searchTenants]);

  return (
    <Select
      value={current}
      onChange={setCurrent}
      options={activeList.map((t) => ({ value: t.code, label: `${t.name} (${t.code})` }))}
      placeholder={t('tenant.placeholder')}
      style={{ width: 240 }}
      size="small"
    />
  );
}
