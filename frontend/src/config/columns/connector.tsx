import { Switch, Popconfirm } from 'antd';
import { Link } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import { formatDateTime } from '@/utils/format';
import { getStatusOptions } from '@/constants/enums';
import type { ConnectorListItem } from '@/types';
import type { TFunction } from 'i18next';
import type { ColumnsType } from 'antd/es/table';

export function getConnectorColumns(
  t: (key: string) => string,
  tc: TFunction,
  onDisable?: (connectorCode: string) => void,
): ColumnsType<ConnectorListItem> {
  void getStatusOptions; // 保留供筛选器用；列内 Switch 不再需要 statusOpts
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
      width: 80,
      // Switch 与其他列表（场景/决策）视觉一致；后端仅 disable 无 enable，DISABLED 态禁用不可逆
      render: (_v: unknown, r: ConnectorListItem) => (
        <Popconfirm
          title={t('action.disableConfirm')}
          onConfirm={() => onDisable?.(r.connectorCode)}
          okText={tc('button.confirm')}
          cancelText={tc('button.cancel')}
          disabled={r.status !== 'ACTIVE'}
        >
          <Switch
            checked={r.status === 'ACTIVE'}
            disabled={r.status !== 'ACTIVE'}
            size="small"
            onClick={(_checked, e) => e.stopPropagation()}
          />
        </Popconfirm>
      ),
    },
    { title: tc('label.createdAt'), dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => formatDateTime(v) },
    { title: tc('label.updatedAt'), dataIndex: 'updatedAt', key: 'updatedAt', render: (v: string) => formatDateTime(v) },
  ];
}
