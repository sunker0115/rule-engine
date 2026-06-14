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

export const MENU_ITEMS: ItemType[] = [
  {
    key: ROUTES.TENANTS,
    icon: <TeamOutlined />,
    label: '租户管理',
  },
  {
    key: ROUTES.SCENES,
    icon: <AppstoreOutlined />,
    label: '场景管理',
  },
  {
    key: ROUTES.RULES,
    icon: <ApartmentOutlined />,
    label: '规则管理',
  },
  {
    key: ROUTES.METRICS,
    icon: <LineChartOutlined />,
    label: '指标管理',
  },
  {
    key: ROUTES.DECISIONS,
    icon: <CheckCircleOutlined />,
    label: '决策管理',
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
