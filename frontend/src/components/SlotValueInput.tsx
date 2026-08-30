import { InputNumber, Input, Switch, Select } from 'antd';
import type { DataType } from '@/types/template';

interface Props {
  dataType: DataType;
  value?: unknown;
  onChange?: (v: unknown) => void;
  disabled?: boolean;
}

/** 按 DataType 渲染 primitive 值输入(脚本参数表默认值格)。 */
export default function SlotValueInput({ dataType, value, onChange, disabled }: Props) {
  switch (dataType) {
    case 'LONG':
    case 'DOUBLE':
    case 'DECIMAL':
      return (
        <InputNumber
          style={{ width: '100%' }}
          value={value as number}
          onChange={onChange}
          disabled={disabled}
          stringMode={dataType === 'DECIMAL'}
          precision={dataType === 'LONG' ? 0 : undefined}
        />
      );
    case 'BOOLEAN':
      return <Switch checked={!!value} onChange={onChange} disabled={disabled} />;
    case 'LIST':
      return (
        <Select
          mode="tags"
          style={{ width: '100%' }}
          value={(value as string[]) ?? []}
          onChange={onChange}
          disabled={disabled}
          open={false}
          placeholder="回车分隔"
        />
      );
    case 'DATE':
    case 'DATETIME':
      return (
        <Input
          value={value as string}
          onChange={(e) => onChange?.(e.target.value)}
          disabled={disabled}
          placeholder={dataType === 'DATE' ? 'YYYY-MM-DD' : 'ISO-8601'}
        />
      );
    case 'STRING':
    default:
      return <Input value={value as string} onChange={(e) => onChange?.(e.target.value)} disabled={disabled} />;
  }
}
