import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import SlotValueInput from './SlotValueInput';

describe('SlotValueInput 按 DataType 渲染 primitive 输入', () => {
  it('BOOLEAN 渲染 Switch 且点击触发 onChange', () => {
    const onChange = vi.fn();
    const { container } = render(<SlotValueInput dataType="BOOLEAN" value={false} onChange={onChange} />);
    const sw = container.querySelector('button[role="switch"]');
    expect(sw).not.toBeNull();
    fireEvent.click(sw!);
    expect(onChange).toHaveBeenCalledWith(true, expect.anything());
  });

  it('LONG 渲染数值输入且录入触发 onChange', () => {
    const onChange = vi.fn();
    const { container } = render(<SlotValueInput dataType="LONG" value={1} onChange={onChange} />);
    const input = container.querySelector('input.ant-input-number-input') as HTMLInputElement;
    expect(input).not.toBeNull();
    fireEvent.change(input, { target: { value: '42' } });
    expect(onChange).toHaveBeenCalledWith(42);
  });

  it('DOUBLE / DECIMAL 亦渲染数值输入', () => {
    const { container: dbl } = render(<SlotValueInput dataType="DOUBLE" />);
    expect(dbl.querySelector('input.ant-input-number-input')).not.toBeNull();
    const { container: dec } = render(<SlotValueInput dataType="DECIMAL" />);
    expect(dec.querySelector('input.ant-input-number-input')).not.toBeNull();
  });

  it('STRING 渲染文本输入且录入触发 onChange', () => {
    const onChange = vi.fn();
    const { container } = render(<SlotValueInput dataType="STRING" value="" onChange={onChange} />);
    const input = container.querySelector('input.ant-input') as HTMLInputElement;
    expect(input).not.toBeNull();
    fireEvent.change(input, { target: { value: 'hello' } });
    expect(onChange).toHaveBeenCalledWith('hello');
  });

  it('DATE / DATETIME 渲染文本输入(ISO 串)带对应 placeholder', () => {
    const { container: date } = render(<SlotValueInput dataType="DATE" />);
    expect(date.querySelector('input[placeholder="YYYY-MM-DD"]')).not.toBeNull();
    const { container: dt } = render(<SlotValueInput dataType="DATETIME" />);
    expect(dt.querySelector('input[placeholder="ISO-8601"]')).not.toBeNull();
  });

  it('LIST 渲染 tags 选择器', () => {
    const { container } = render(<SlotValueInput dataType="LIST" value={['a', 'b']} />);
    expect(container.querySelector('.ant-select')).not.toBeNull();
    expect(container.querySelector('.ant-select-selection-item')).not.toBeNull();
  });

  it('disabled 透传到底层控件', () => {
    const { container } = render(<SlotValueInput dataType="STRING" disabled />);
    expect((container.querySelector('input.ant-input') as HTMLInputElement).disabled).toBe(true);
  });
});
