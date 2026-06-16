import { Tag, Button, Popconfirm } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { formatDateTime } from '@/utils/format';
import { getStatusOptions, labelOf, colorOf } from '@/constants/enums';
import type { ConnectorListItem } from '@/types';
import type { TFunction } from 'i18next';
import type { ColumnsType } from 'antd/es/table';

export function getConnectorColumns(
  t: (key: string) => string,
  tc: TFunction,
  onDisable?: (connectorCode: string) => void,
): ColumnsType<ConnectorListItem> {
  const statusOpts = getStatusOptions(tc);
  return [
    { title: tc('label.tenant'), dataIndex: 'tenantId', key: 'tenantId', width: 70 },
    {
      title: t('column.connectorCode'),
      dataIndex: 'connectorCode',
      key: 'connectorCode',
      render: (v: string) => <Link to={route(ROUTES.CONNECTOR_DETAIL, { connectorCode: v })}>{v}</Link>,
    },
    { title: t('column.name'), dataIndex: 'name', key: 'name' },
    {
      title: t('column.status'),
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (v?: string) => (v ? <Tag color={colorOf(statusOpts, v)}>{labelOf(statusOpts, v)}</Tag> : <span>-</span>),
    },
    { title: tc('label.createdAt'), dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => formatDateTime(v) },
    { title: tc('label.updatedAt'), dataIndex: 'updatedAt', key: 'updatedAt', render: (v: string) => formatDateTime(v) },
    {
      title: t('column.action'),
      key: 'action',
      width: 90,
      // 后端仅 disable 无 enable：ACTIVE 给 Popconfirm 禁用按钮，DISABLED 给禁用态按钮（不做两向开关）
      render: (_v: unknown, r: ConnectorListItem) =>
        r.status === 'ACTIVE' ? (
          <Popconfirm
            title={t('action.disableConfirm')}
            onConfirm={() => onDisable?.(r.connectorCode)}
            okText={tc('button.confirm')}
            cancelText={tc('button.cancel')}
          >
            <Button type="link" danger size="small" onClick={(e) => e.stopPropagation()}>
              {t('action.disable')}
            </Button>
          </Popconfirm>
        ) : (
          <Button type="link" danger size="small" disabled onClick={(e) => e.stopPropagation()}>
            {t('action.disable')}
          </Button>
        ),
    },
  ];
}
