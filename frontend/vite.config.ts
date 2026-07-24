import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: false,
  },
  server: {
    port: 5173,
    proxy: {
      '/admin': 'http://localhost:8080',
      '/api': 'http://localhost:8080',
      '/sdk': 'http://localhost:8080',
    },
  },
  build: {
    // antd 核心组件整库约 590KB（gzip ~164KB），是稳定的框架 vendor chunk，
    // 已按库边界拆出 react/antd-rc/codemirror/i18n 等独立 chunk；阈值上调以容纳 antd 框架块
    // antd/plots/vendor 天然大于 600KB；plots 是 lazy，首屏不加载；设 1100 消除噪音警告
    chunkSizeWarningLimit: 1100,
    rollupOptions: {
      // antd5 / antd-rc / react 内部存在循环 import，manualChunks 按库边界拆分后
      // Rollup 检测到 chunk 间循环会发出此警告，但产物功能完全正确；精确 suppress 掉噪音。
      onwarn(warning, defaultWarn) {
        if (warning.message.startsWith('Circular chunk')) return;
        defaultWarn(warning);
      },
      output: {
        // 按 node_modules 库边界保守拆分 vendor chunk，避免首屏主 chunk 过大
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined;
          if (id.includes('monaco-editor') || id.includes('@monaco-editor')) return 'monaco';
          if (id.includes('codemirror') || id.includes('@codemirror') || id.includes('@lezer')) return 'codemirror';
          if (id.includes('@ant-design/icons')) return 'antd-icons';
          // plots + 整个 @antv/* G2 生态单独拆出（路由 lazy，只访问 /effectiveness 才加载）
          if (id.includes('@ant-design/plots') || id.includes('@antv/')) return 'plots';
          if (id.includes('rc-') || id.includes('@rc-component')) return 'antd-rc';
          if (id.includes('antd') || id.includes('@ant-design')) return 'antd';
          if (id.includes('react-router') || id.includes('react-dom') || id.includes('/react/') || id.includes('scheduler')) return 'react';
          if (id.includes('i18next') || id.includes('react-i18next')) return 'i18n';
          return 'vendor';
        },
      },
    },
  },
});
