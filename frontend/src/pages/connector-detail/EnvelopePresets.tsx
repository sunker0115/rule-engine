import { Space, Button, Typography, message } from 'antd';
import { useTranslation } from 'react-i18next';
import type { ResponseMapping } from '@/types';

interface Props {
  /** 应用预设：用预设的 successWhen + valuePath 覆盖当前响应映射 */
  onApply: (mapping: ResponseMapping) => void;
}

/** 信封预设：一键填充常见响应信封的成功判定与取值路径（设计 §10 易用性） */
const PRESETS: Record<'codeMsgData' | 'bareJson' | 'successData', ResponseMapping> = {
  // {code,msg,data}：code==0 视为成功，业务值在 data 下
  codeMsgData: { successWhen: { path: 'code', op: 'EQ', value: 0 }, valuePath: 'data.value' },
  // 裸 JSON：无信封，HTTP 2xx 即成功（path 空 → 仅依赖状态码），值在根
  bareJson: { successWhen: { path: '', op: 'EQ', value: '' }, valuePath: 'value' },
  // {success,data}：success==true 视为成功
  successData: { successWhen: { path: 'success', op: 'EQ', value: true }, valuePath: 'data.value' },
};

export default function EnvelopePresets({ onApply }: Props) {
  const { t } = useTranslation('connector');

  const apply = (key: keyof typeof PRESETS) => {
    onApply({
      successWhen: { ...PRESETS[key].successWhen },
      valuePath: PRESETS[key].valuePath,
    });
    message.success(t('preset.applied'));
  };

  return (
    <div style={{ marginBottom: 16 }}>
      <Space size="middle" align="center" wrap>
        <Typography.Text type="secondary">{t('preset.hint')}</Typography.Text>
        <Button size="small" onClick={() => apply('codeMsgData')}>{t('preset.codeMsgData')}</Button>
        <Button size="small" onClick={() => apply('bareJson')}>{t('preset.bareJson')}</Button>
        <Button size="small" onClick={() => apply('successData')}>{t('preset.successData')}</Button>
      </Space>
    </div>
  );
}
