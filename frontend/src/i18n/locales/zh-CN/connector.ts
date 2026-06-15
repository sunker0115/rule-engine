import type { ConnectorTranslation } from '../../types';

const connector: ConnectorTranslation = {
  title: { list: '连接器列表', detail: '连接器详情' },
  action: { create: '新建连接器' },
  column: {
    connectorCode: '连接器编码',
    name: '名称',
    status: '状态',
  },
  searchPlaceholder: '搜索名称或编码',
  detail: { notFound: '连接器不存在' },
};

export default connector;
