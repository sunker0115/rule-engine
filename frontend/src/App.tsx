import { useState, useCallback, useEffect } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, Typography, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { getMenuItems } from '@/config/menu';
import { useTenantStore } from '@/store/tenantStore';

const { Header, Sider, Content } = Layout;

const LANG_OPTIONS = [
  { value: 'zh-CN', label: '中文' },
  { value: 'en', label: 'English' },
];

export default function App() {
  const navigate = useNavigate();
  const location = useLocation();
  const { t, i18n } = useTranslation('common');
  const [actorId] = useState(() => localStorage.getItem('actorId') || 'anonymous');
  const tenantInit = useTenantStore((s) => s.init);

  useEffect(() => { tenantInit(); }, [tenantInit]);

  const segments = location.pathname.split('/').filter(Boolean);
  const selectedKey = segments.length > 0 ? `/${segments[0]}` : '/scenes';

  const handleLangChange = useCallback((lang: string) => {
    i18n.changeLanguage(lang);
  }, [i18n]);

  return (
    <Layout style={{ height: '100vh' }}>
      <Header style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '0 24px',
        background: '#001529',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
          <Typography.Title level={4} style={{ color: '#fff', margin: 0 }}>
            {t('app.title')}
          </Typography.Title>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Select
            size="small"
            value={i18n.language}
            onChange={handleLangChange}
            options={LANG_OPTIONS}
            style={{ width: 80 }}
          />
          <Typography.Text style={{ color: 'rgba(255,255,255,0.65)' }}>
            {t('header.actorLabel')}：{actorId}
          </Typography.Text>
        </div>
      </Header>
      <Layout>
        <Sider width={200} style={{ background: '#fff' }}>
          <Menu
            mode="inline"
            selectedKeys={[selectedKey]}
            items={getMenuItems(t)}
            onClick={({ key }) => navigate(key)}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Sider>
        <Content style={{ padding: 24, background: '#f5f5f5', overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
