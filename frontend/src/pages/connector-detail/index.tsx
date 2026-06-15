import { Button, Empty } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ROUTES } from '@/constants/routes';

// 占位详情页：编辑器与自助测试面板在 P4-T3/T4 实现，本任务仅挂路由避免列表行点击 404。
export default function ConnectorDetail() {
  const navigate = useNavigate();
  const { t } = useTranslation('connector');
  const tc = useTranslation('common').t;
  return (
    <>
      <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(ROUTES.CONNECTORS)} style={{ marginBottom: 16 }}>
        {tc('button.back')}
      </Button>
      <Empty description={t('title.detail')} />
    </>
  );
}
