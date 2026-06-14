import { useState, useCallback } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, Typography, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { MENU_ITEMS } from '@/config/menu';
import TenantSelector from '@/components/tenant-selector';

const { Header, Sider, Content } = Layout;

const LANG_OPTIONS = [
  { value: 'zh-CN', label: '中文' },
];

export default function App() {
  const navigate = useNavigate();
  const location = useLocation();
  const { t, i18n } = useTranslation('common');
  const [actorId] = useState(() => localStorage.getItem('actorId') || 'anonymous');

  const segments = location.pathname.split('/').filter(Boolean);
  const selectedKey = segments.length > 0 ? `/${segments[0]}` : '/scenes';

  const handleLangChange = useCallback((lang: string) => {
    i18n.changeLanguage(lang);
  }, [i18n]);

  return (
    <Layout style={{ minHeight: '100vh' }}>
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
          <TenantSelector />
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
            items={MENU_ITEMS}
            onClick={({ key }) => navigate(key)}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Sider>
        <Content style={{ padding: 24, background: '#f5f5f5' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
