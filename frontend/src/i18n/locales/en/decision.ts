import type { DecisionTranslation } from '../../types';

const decision: DecisionTranslation = {
  title: { list: 'Decisions' },
  action: { create: 'New Decision', edit: 'Edit Decision' },
  column: {
    code: 'Code',
    name: 'Name',
    priority: 'Priority',
    status: 'Status',
    description: 'Description',
    createdAt: 'Created',
    updatedAt: 'Updated',
  },
  form: {
    code: 'Code',
    codePlaceholder: 'e.g. REJECT / REVIEW / PASS',
    codeDisabled: 'Cannot be changed after creation',
    name: 'Name',
    priority: 'Priority',
    priorityExtra: 'Lower value = higher priority',
    description: 'Description',
  },
};

export default decision;
