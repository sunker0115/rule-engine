import type { TFunction } from 'i18next';
import type { ItemType } from 'antd/es/menu/interface';
import {
  TeamOutlined,
  AppstoreOutlined,
  ApartmentOutlined,
  LineChartOutlined,
  CheckCircleOutlined,
  HistoryOutlined,
  AuditOutlined,
  ClockCircleOutlined,
  ImportOutlined,
} from '@ant-design/icons';
import { ROUTES } from '@/constants/routes';

export function getMenuItems(t: TFunction): ItemType[] {
  return [
    {
      key: ROUTES.TENANTS,
      icon: <TeamOutlined />,
      label: t('menu.tenants'),
    },
    {
      key: ROUTES.SCENES,
      icon: <AppstoreOutlined />,
      label: t('menu.scenes'),
    },
    {
      key: ROUTES.RULES,
      icon: <ApartmentOutlined />,
      label: t('menu.rules'),
    },
    {
      key: ROUTES.METRICS,
      icon: <LineChartOutlined />,
      label: t('menu.metrics'),
    },
    {
      key: ROUTES.DECISIONS,
      icon: <CheckCircleOutlined />,
      label: t('menu.decisions'),
    },
    { type: 'divider' },
    {
      key: ROUTES.SESSIONS,
      icon: <HistoryOutlined />,
      label: t('menu.sessions'),
    },
    {
      key: ROUTES.AUDIT_LOGS,
      icon: <AuditOutlined />,
      label: t('menu.auditLogs'),
    },
    { type: 'divider' },
    {
      key: ROUTES.JOBS,
      icon: <ClockCircleOutlined />,
      label: t('menu.jobs'),
    },
    {
      key: ROUTES.IMPORT_EXPORT,
      icon: <ImportOutlined />,
      label: t('menu.importExport'),
    },
  ];
}
