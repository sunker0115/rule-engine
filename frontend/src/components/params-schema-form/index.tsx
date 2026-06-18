import { Form, Input, InputNumber, Select, Switch } from 'antd';
import type { ReactNode } from 'react';

interface Props {
  schema: Record<string, unknown>;
  value?: Record<string, unknown>;
  onChange?: (value: Record<string, unknown>) => void;
}

interface SchemaProp {
  type?: string;
  title?: string;
  enum?: unknown[];
  properties?: Record<string, unknown>;
  items?: Record<string, unknown>;
}

export default function ParamsSchemaForm({ schema, value, onChange }: Props) {
  const s = schema as { type?: string; required?: string[]; properties?: Record<string, SchemaProp> };
  const properties = s.properties ?? {};
  const requiredFields: string[] = s.required ?? [];
  const entries = Object.entries(properties);

  if (entries.length === 0) return null;

  const renderField = (key: string, prop: SchemaProp, path: string[]): ReactNode => {
    const fieldPath = [...path, key];
    const fieldKey = fieldPath.join('.');
    const isRequired = requiredFields.includes(key);
    const label = prop.title ?? key;

    // enum → Select
    if (prop.enum && Array.isArray(prop.enum)) {
      return (
        <Form.Item key={fieldKey} label={label} required={isRequired}>
          <Select
            value={value?.[key]}
            onChange={(v) => onChange?.({ ...value, [key]: v })}
            options={prop.enum.map((e) => ({ value: e, label: String(e) }))}
            allowClear
          />
        </Form.Item>
      );
    }

    switch (prop.type) {
      case 'integer':
      case 'number':
        return (
          <Form.Item key={fieldKey} label={label} required={isRequired}>
            <InputNumber
              value={value?.[key] as number | undefined}
              onChange={(v) => onChange?.({ ...value, [key]: v })}
              style={{ width: '100%' }}
            />
          </Form.Item>
        );

      case 'boolean':
        return (
          <Form.Item key={fieldKey} label={label} required={isRequired} valuePropName="checked">
            <Switch
              checked={value?.[key] as boolean}
              onChange={(v) => onChange?.({ ...value, [key]: v })}
            />
          </Form.Item>
        );

      case 'object':
        return (
          <Form.Item key={fieldKey} label={label} required={isRequired}>
            <Input.TextArea
              value={value?.[key] ? JSON.stringify(value[key], null, 2) : ''}
              onChange={(e) => {
                try {
                  const parsed = JSON.parse(e.target.value);
                  onChange?.({ ...value, [key]: parsed });
                } catch {
                  // 用户正在输入，忽略解析错误
                }
              }}
              rows={3}
              style={{ fontFamily: 'monospace' }}
            />
          </Form.Item>
        );

      default:
        // string or unknown → Input
        return (
          <Form.Item key={fieldKey} label={label} required={isRequired}>
            <Input
              value={value?.[key] as string}
              onChange={(e) => onChange?.({ ...value, [key]: e.target.value })}
            />
          </Form.Item>
        );
    }
  };

  return (
    <div>
      {entries.map(([key, prop]) => renderField(key, prop, []))}
    </div>
  );
}
