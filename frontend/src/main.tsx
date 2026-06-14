import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { Locale } from 'antd/es/locale';
import { RouterProvider } from 'react-router-dom';
import './i18n'; // 初始化 i18next（必须在 App 之前 load）
import { router } from './router';

/** antd 语言包映射（与 i18n 语言代码对齐） */
const ANTD_LOCALES: Record<string, Locale> = {
  'zh-CN': zhCN,
};

function getAntdLocale(): Locale {
  const lang = localStorage.getItem('i18nLang') || 'zh-CN';
  return ANTD_LOCALES[lang] || zhCN;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={getAntdLocale()}>
      <RouterProvider router={router} />
    </ConfigProvider>
  </React.StrictMode>
);
