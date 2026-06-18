import { DatePicker, Alert } from 'antd';
import dayjs, { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { TimeWindowParams } from '@/types';

const { RangePicker } = DatePicker;

interface Props {
  value?: TimeWindowParams;
  onChange?: (value: TimeWindowParams) => void;
}

/** 生效时段录入：起止时间（含时分秒）转 epoch millis，写入 TIME_WINDOW pre-gate params。清空即移除时段约束。 */
export default function TimeWindowPicker({ value = {}, onChange }: Props) {
  const { t } = useTranslation('rule');

  // 两端皆空时传 null（而非 [null,null]），保证清除按钮(X)能整体清空、显示 placeholder
  const hasAny = value.fromEpochMilli != null || value.toEpochMilli != null;
  const range: [Dayjs | null, Dayjs | null] | null = hasAny
    ? [
        value.fromEpochMilli != null ? dayjs(value.fromEpochMilli) : null,
        value.toEpochMilli != null ? dayjs(value.toEpochMilli) : null,
      ]
    : null;

  return (
    <div>
      <Alert
        type="info"
        showIcon
        message={t('preGate.descTimeWindow')}
        style={{ marginBottom: 16, fontSize: 13 }}
      />
      <RangePicker
        showTime
        allowEmpty={[true, true]}
        value={range}
        onChange={(dates) =>
          onChange?.({
            fromEpochMilli: dates?.[0] ? dates[0].valueOf() : undefined,
            toEpochMilli: dates?.[1] ? dates[1].valueOf() : undefined,
          })
        }
        style={{ width: '100%' }}
      />
    </div>
  );
}
