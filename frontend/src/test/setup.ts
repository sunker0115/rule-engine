import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';

// jsdom 无 matchMedia，antd 响应式组件（Table/Grid）挂载时会调用它
if (!window.matchMedia) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
}
