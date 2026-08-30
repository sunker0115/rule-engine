import type { TFunction } from 'i18next';
import type { ItemType } from 'antd/es/menu/interface';
import {
  TeamOutlined,
  AppstoreOutlined,
  ApartmentOutlined,
  LineChartOutlined,
  ApiOutlined,
  CheckCircleOutlined,
  HistoryOutlined,
  AuditOutlined,
  ClockCircleOutlined,
  ImportOutlined,
  BarChartOutlined,
  FileTextOutlined,
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
      key: ROUTES.TEMPLATES,
      icon: <FileTextOutlined />,
      label: t('menu.templates'),
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
      key: ROUTES.CONNECTORS,
      icon: <ApiOutlined />,
      label: t('menu.connectors'),
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
      key: ROUTES.EFFECTIVENESS,
      icon: <BarChartOutlined />,
      label: t('menu.effectiveness'),
    },
    {
      key: ROUTES.AUDIT_LOGS,
      icon: <AuditOutlined />,
      label: t('menu.auditLogs'),
    },
    { type: 'divider' },
    {
      key: ROUTES.SCHEDULED_TASKS,
      icon: <ClockCircleOutlined />,
      label: t('menu.scheduledTasks'),
    },
    {
      key: ROUTES.IMPORT_EXPORT,
      icon: <ImportOutlined />,
      label: t('menu.importExport'),
    },
  ];
}
