import { Slider, InputNumber, Switch, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { RolloutParams } from '@/types';

interface Props {
  value?: RolloutParams;
  onChange?: (value: RolloutParams) => void;
}

export default function RolloutSlider({ value = {}, onChange }: Props) {
  const { t } = useTranslation('rule');

  const useBucketRange = value.bucketStart !== undefined && value.bucketEnd !== undefined;

  const handlePercentChange = (percentage: number | null) => {
    onChange?.({ ...value, percentage: percentage ?? 0, bucketStart: undefined, bucketEnd: undefined });
  };

  const handleBucketStartChange = (start: number | null) => {
    onChange?.({ ...value, bucketStart: start ?? 0, bucketEnd: value.bucketEnd ?? 100 });
  };

  const handleBucketEndChange = (end: number | null) => {
    onChange?.({ ...value, bucketStart: value.bucketStart ?? 0, bucketEnd: end ?? 100 });
  };

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Typography.Text>{t('preGate.modePercent')}</Typography.Text>
        <Switch
          checked={!useBucketRange}
          onChange={(checked) => {
            if (checked) {
              onChange?.({
                percentage: value.percentage ?? 100,
                bucketStart: undefined,
                bucketEnd: undefined,
              });
            } else {
              onChange?.({ bucketStart: 0, bucketEnd: 100, percentage: undefined });
            }
          }}
        />
        <Typography.Text>{t('preGate.modeBucket')}</Typography.Text>
      </Space>

      {!useBucketRange ? (
        <div>
          <Typography.Text>
            {t('preGate.labelRollout')} {value.percentage ?? 100}%
          </Typography.Text>
          <Slider min={0} max={100} value={value.percentage ?? 100} onChange={handlePercentChange} />
          <InputNumber
            min={0}
            max={100}
            value={value.percentage ?? 100}
            onChange={handlePercentChange}
            style={{ width: '100%' }}
          />
        </div>
      ) : (
        <div>
          <Space>
            <div>
              <Typography.Text>{t('preGate.labelBucketStart')}</Typography.Text>
              <InputNumber min={0} max={99} value={value.bucketStart ?? 0} onChange={handleBucketStartChange} />
            </div>
            <div>
              <Typography.Text>{t('preGate.labelBucketEnd')}</Typography.Text>
              <InputNumber min={1} max={100} value={value.bucketEnd ?? 100} onChange={handleBucketEndChange} />
            </div>
          </Space>
          <div style={{ marginTop: 8 }}>
            <Typography.Text>
              {t('preGate.labelRange')} [{value.bucketStart ?? 0}, {value.bucketEnd ?? 100})
            </Typography.Text>
          </div>
        </div>
      )}
    </div>
  );
}
