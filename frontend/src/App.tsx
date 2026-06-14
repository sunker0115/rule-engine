import { useState } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, Typography } from 'antd';
import { MENU_ITEMS } from '@/config/menu';
import TenantSelector from '@/components/tenant-selector';

const { Header, Sider, Content } = Layout;

export default function App() {
  const navigate = useNavigate();
  const location = useLocation();
  const [actorId] = useState(() => localStorage.getItem('actorId') || 'anonymous');

  const segments = location.pathname.split('/').filter(Boolean);
  const selectedKey = segments.length > 0 ? `/${segments[0]}` : '/scenes';

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
            规则引擎运营平台
          </Typography.Title>
          <TenantSelector />
        </div>
        <Typography.Text style={{ color: 'rgba(255,255,255,0.65)' }}>
          操作人：{actorId}
        </Typography.Text>
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
