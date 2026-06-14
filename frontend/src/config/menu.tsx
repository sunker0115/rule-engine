import { useMemo } from 'react';
import type { ItemType } from 'antd/es/menu/interface';
import {
  AppstoreOutlined,
  ApartmentOutlined,
  LineChartOutlined,
  CheckCircleOutlined,
  SettingOutlined,
  HistoryOutlined,
  AuditOutlined,
  ClockCircleOutlined,
  ImportOutlined,
} from '@ant-design/icons';
import { ROUTES, route } from '@/constants/routes';

export function useMenuItems(sceneCode: string | null): ItemType[] {
  return useMemo(() => {
    const sceneItems: ItemType[] = sceneCode ? [
      {
        key: route(ROUTES.SCENE_RULES, { sceneCode }),
        icon: <ApartmentOutlined />,
        label: '规则列表',
      },
      {
        key: `/sessions?sceneCode=${sceneCode}&status=HIT&status=BLOCKED`,
        icon: <HistoryOutlined />,
        label: '评估会话',
      },
      {
        key: route(ROUTES.SCENE_DETAIL, { sceneCode }),
        icon: <SettingOutlined />,
        label: '场景设置',
      },
      { type: 'divider' as const },
    ] : [];

    return [
      ...sceneItems,
      {
        key: ROUTES.SCENES,
        icon: <AppstoreOutlined />,
        label: 'Scene 管理',
      },
      { type: 'divider' as const },
      {
        key: ROUTES.METRICS,
        icon: <LineChartOutlined />,
        label: 'Metric 管理',
      },
      {
        key: ROUTES.DECISIONS,
        icon: <CheckCircleOutlined />,
        label: 'Decision 管理',
      },
      { type: 'divider' as const },
      {
        key: ROUTES.AUDIT_LOGS,
        icon: <AuditOutlined />,
        label: '审计日志',
      },
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
  }, [sceneCode]);
}
