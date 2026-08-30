/** 从 payloadSchema 提取字段名列表 + 类型映射 */
export function extractPayloadSchema(schema: unknown): { names: string[]; types: Record<string, string> } {
  if (!schema) return { names: [], types: {} };
  const names: string[] = [];
  const types: Record<string, string> = {};
  if (Array.isArray(schema)) {
    for (const f of schema as Record<string, unknown>[]) {
      const n = f.name as string;
      if (n) { names.push(n); types[n] = (f.type as string) ?? 'string'; }
    }
  } else if (typeof schema === 'object') {
    const props = (schema as Record<string, unknown>).properties;
    if (props && typeof props === 'object') {
      for (const [n, def] of Object.entries(props)) {
        names.push(n);
        types[n] = (def as Record<string, unknown>).type as string ?? 'string';
      }
    }
  }
  return { names, types };
}
