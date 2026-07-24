import type { TemplateTranslation } from '../../types';

const template: TemplateTranslation = {
  title: { list: 'Rule Templates', editor: 'Edit Template', instantiate: 'Instantiate Template' },
  action: {
    create: 'Create Template', edit: 'Edit', publish: 'Publish', publishConfirm: 'Publish this template?',
    disable: 'Disable', disableConfirm: 'Disable this template?',
    instantiate: 'Instantiate',
    back: 'Back', save: 'Save', saveSuccess: 'Saved',
  },
  column: {
    name: 'Name', code: 'Code', kind: 'Kind', version: 'Version',
    slots: 'Slots', status: 'Status',
    createdAt: 'Created At', actions: 'Actions',
  },
  enum: {
    version: 'v',
    status: { DRAFT: 'Draft', PUBLISHED: 'Published', DISABLED: 'Disabled' },
    dataType: {
      LONG: 'Long', DOUBLE: 'Double', DECIMAL: 'Decimal', STRING: 'String',
      BOOLEAN: 'Boolean', DATE: 'Date', DATETIME: 'DateTime', LIST: 'List',
    },
  },
  form: {
    name: 'Name', code: 'Code', kind: 'Kind',
    description: 'Description',
    basicInfo: 'Basic Info',
    bodySkeleton: 'Body Skeleton (with defaults)',
    slots: 'Slots',
    slotKey: 'Key', slotKeyPlaceholder: 'param key',
    slotLabel: 'Label', slotLabelPlaceholder: 'display name for ops',
    slotDataType: 'Data Type',
    slotRequired: 'Required',
    addSlot: 'Add Slot',
    bindings: 'Slot Bindings',
    bindingSlot: 'Bind to Slot',
    jsonPointer: 'JSON Pointer',
    jsonPointerPlaceholder: '/conditionAst/children/0/params/threshold',
    addBinding: 'Add Binding',
  },
  instantiate: {
    selectScene: 'Target Scene',
    ruleCode: 'Rule Code', ruleName: 'Rule Name',
    fillSlots: 'Fill Slots',
    submit: 'Instantiate',
    success: 'Instantiated successfully!',
    goToRule: 'Go to Editor',
  },
};

export default template;
