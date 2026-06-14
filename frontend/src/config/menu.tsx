import type { ItemType } from 'antd/es/menu/interface';
import {
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

export const MENU_ITEMS: ItemType[] = [
  {
    key: ROUTES.SCENES,
    icon: <AppstoreOutlined />,
    label: 'Scene',
  },
  {
    key: ROUTES.RULES,
    icon: <ApartmentOutlined />,
    label: 'Rule',
  },
  {
    key: ROUTES.METRICS,
    icon: <LineChartOutlined />,
    label: 'Metric',
  },
  {
    key: ROUTES.DECISIONS,
    icon: <CheckCircleOutlined />,
    label: 'Decision',
  },
  { type: 'divider' },
  {
    key: ROUTES.SESSIONS,
    icon: <HistoryOutlined />,
    label: '评估会话',
  },
  {
    key: ROUTES.AUDIT_LOGS,
    icon: <AuditOutlined />,
    label: '审计日志',
  },
  { type: 'divider' },
  {
    key: ROUTES.JOBS,
    icon: <ClockCircleOutlined />,
    label: 'Job 管理',
  },
  {
    key: ROUTES.IMPORT_EXPORT,
    icon: <ImportOutlined />,
    label: '导入导出',
  },
];
