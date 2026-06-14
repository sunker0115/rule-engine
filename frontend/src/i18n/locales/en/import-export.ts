import type { ImportExportTranslation } from '../../types';

const importExport: ImportExportTranslation = {
  title: { page: 'Import / Export' },
  tab: { export: 'Export', import: 'Import' },
  export: {
    scope: 'Export Scope',
    byRuleIds: 'By Rule IDs',
    byScene: 'By Scene',
    all: 'All Tenants',
    download: 'Download',
    summary: 'Exporting: {rules} rules + {scenes} scenes + {metrics} metrics + {decisions} decisions',
    downloadComplete: 'Download complete',
    sceneCodePlaceholder: 'Scene Code',
    ruleIdsPlaceholder: '1,2,3',
  },
  import: {
    upload: 'Click or drag to upload Bundle JSON file',
    uploadHint: 'Supports .json files',
    importComplete: 'Import complete',
    preview: 'Import Preview',
    previewTitle: 'The following will be imported into the target tenant',
    existing: 'Existing, create draft version',
    newDraft: 'New Draft',
    skip: 'Skip',
    review: 'Needs Review',
    execute: 'Execute Import',
    result: {
      title: 'Import Result',
      rulesImported: 'Rules Imported',
      scenesCreated: 'Scenes Created',
      scenesSkipped: 'Scenes Skipped',
      metricsCreated: 'Metrics Created',
      metricsSkipped: 'Metrics Skipped',
      metricsReview: 'Metrics Review',
      decisionsCreated: 'Decisions Created',
      decisionsSkipped: 'Decisions Skipped',
    },
    error: {
      parseError: 'Invalid file format',
      missingScene: 'Referenced scene does not exist',
    },
  },
};

export default importExport;
