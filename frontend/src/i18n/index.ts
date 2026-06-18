import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import zhCN from './locales/zh-CN';
import en from './locales/en';

/**
 * i18n 初始化 —— 类型安全的多语言系统。
 *
 * 开发人员新增翻译：
 * 1. 在 types.ts 对应接口加 key
 * 2. 在 locales/zh-CN/ 和 locales/en/ 对应文件加值
 * 3. 组件中 useTranslation('namespace') + t('key.path')
 *
 * 删除翻译：删 key → TS 编译报错 → grep 引用位置 → 删干净。
 *
 * 语言切换：localStorage 持久化，Header 下拉切换。
 */
i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: { 'zh-CN': zhCN, en },
    fallbackLng: 'zh-CN',
    defaultNS: 'common',
    detection: {
      order: ['localStorage', 'navigator'],
      lookupLocalStorage: 'i18nLang',
      caches: ['localStorage'],
    },
    interpolation: {
      escapeValue: false, // React 已经防 XSS
    },
  });

export default i18n;
