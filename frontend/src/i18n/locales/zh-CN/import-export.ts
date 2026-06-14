import type { ImportExportTranslation } from '../../types';

const importExport: ImportExportTranslation = {
  title: { page: '导入导出' },
  tab: { export: '导出', import: '导入' },
  export: {
    scope: '导出范围',
    byRuleIds: '按规则 ID',
    byScene: '按 Scene',
    all: '全租户',
    download: '下载',
    summary: '导出内容：{rules} 条规则 + {scenes} 个 Scene + {metrics} 个 Metric + {decisions} 个 Decision',
    downloadComplete: '下载完成',
    sceneCodePlaceholder: 'Scene Code',
    ruleIdsPlaceholder: '1,2,3',
  },
  import: {
    upload: '点击或拖拽上传 Bundle JSON 文件',
    uploadHint: '支持 .json 文件',
    importComplete: '导入完成',
    preview: '导入预览',
    previewTitle: '以下内容将被导入到目标租户',
    existing: '已存在，将追加草稿版本',
    newDraft: '新草稿',
    skip: '跳过',
    review: '需人工审核',
    execute: '执行导入',
    result: {
      title: '导入结果',
      rulesImported: '规则导入',
      scenesCreated: 'Scene 新建',
      scenesSkipped: 'Scene 跳过',
      metricsCreated: 'Metric 新建',
      metricsSkipped: 'Metric 跳过',
      metricsReview: 'Metric 需审核',
      decisionsCreated: 'Decision 新建',
      decisionsSkipped: 'Decision 跳过',
    },
    error: {
      parseError: '文件格式错误',
      missingScene: '规则引用的 Scene 不存在',
    },
  },
};

export default importExport;
