/**
 * 前端功能开关。与后端 @ConditionalOnProperty 对齐：
 * 后端默认关闭的能力，前端菜单/路由也须默认隐藏，避免用户点进去 404。
 */
export const FEATURES = {
  // 参数化规则模板（后端 rule.template.enabled 默认关闭）
  templates: false,
} as const;
