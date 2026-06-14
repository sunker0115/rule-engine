import type { CommonTranslation } from '../../types';

const common: CommonTranslation = {
  app: { title: 'Rule Engine Console' },
  header: { actorLabel: 'Operator' },
  tenant: { placeholder: 'Select Tenant' },
  button: {
    back: 'Back',
    save: 'Save',
    cancel: 'Cancel',
    edit: 'Edit',
    delete: 'Delete',
    confirm: 'Confirm',
    submit: 'Submit',
    refresh: 'Refresh',
    copy: 'Copy JSON',
  },
  label: {
    id: 'ID',
    code: 'Code',
    name: 'Name',
    status: 'Status',
    description: 'Description',
    actions: 'Actions',
    createdAt: 'Created',
    updatedAt: 'Updated',
    none: '-',
    yes: 'Yes',
    no: 'No',
    all: 'All',
    tenant: 'Tenant',
    searchPlaceholder: 'Search by code or name',
    paginationTotal: '{{total}} total',
  },
  enum: {
    status: { ACTIVE: 'Enabled', DISABLED: 'Disabled' },
    actorType: { USER: 'User', SYSTEM: 'System', JOB: 'Job' },
  },
  message: {
    createSuccess: 'Created',
    updateSuccess: 'Updated',
    deleteSuccess: 'Deleted',
    saveSuccess: 'Saved',
    loadError: 'Load failed',
    confirmDelete: 'Confirm delete? This cannot be undone',
    enabled: 'Enabled',
    disabled: 'Disabled',
  },
  validation: {
    required: 'Required',
    jsonFormat: 'Invalid JSON',
  },
  title: {
    tenantList: 'Tenants',
  },
  menu: {
    tenants: 'Tenants',
    scenes: 'Scenes',
    rules: 'Rules',
    metrics: 'Metrics',
    decisions: 'Decisions',
    sessions: 'Sessions',
    auditLogs: 'Audit Logs',
    jobs: 'Jobs',
    importExport: 'Import/Export',
  },
};

export default common;
