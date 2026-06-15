import type { ConnectorTranslation } from '../../types';

const connector: ConnectorTranslation = {
  title: { list: 'Connectors', detail: 'Connector Detail' },
  action: { create: 'New Connector' },
  column: {
    connectorCode: 'Connector Code',
    name: 'Name',
    status: 'Status',
  },
  searchPlaceholder: 'Search by name or code',
  detail: { notFound: 'Connector not found' },
};

export default connector;
