import type { TemplateTranslation } from '../../types';

const template: TemplateTranslation = {
  title: { list: 'Rule Templates', editor: 'Edit Template', instantiate: 'Instantiate Template' },
  action: {
    create: 'Create Template', edit: 'Edit', publish: 'Publish', publishConfirm: 'Publish this template?',
    disable: 'Disable', disableConfirm: 'Disable this template?',
    instantiate: 'Instantiate',
    remove: 'Remove',
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
    slotKey: 'Key',
    slotLabel: 'Label', slotLabelPlaceholder: 'display name for ops',
    slotDataType: 'Data Type',
    slotRequired: 'Required',
    referenceScene: 'Reference Scene',
    referenceScenePlaceholder: 'Select a scene to load metric/field/decision metadata',
    referenceSceneHint: 'Authoring metadata only (metrics/fields/decisions); not saved into the template',
    parameterize: 'Parameterize Position',
    parameterizePlaceholder: 'Select a position in the rule body to parameterize',
    parameterizeScriptHint: 'For scripts, declare slots via the “Parameterize” switch in the params table below',
    slotEnum: 'Enum', slotMin: 'Min', slotMax: 'Max',
  },
  instantiate: {
    selectTenant: 'Target Tenant',
    selectScene: 'Target Scene',
    ruleCode: 'Rule Code', ruleName: 'Rule Name',
    triggerEventTypes: 'Event Types',
    fillSlots: 'Fill Slots',
    submit: 'Instantiate',
    success: 'Instantiated successfully!',
    goToRule: 'Go to Editor',
  },
};

export default template;
