import { useEffect } from 'react';
import { Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useTenantStore } from '@/store/tenantStore';
import { useSceneStore } from '@/store/sceneStore';
import { ROUTES, route } from '@/constants/routes';

interface SceneOption {
  value: string;
  label: string;
  searchText: string;
}

export default function SceneSelector() {
  const { t } = useTranslation('common');
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const { list, selectedSceneCode, loadList, setSelectedScene } = useSceneStore();

  useEffect(() => {
    if (currentId) loadList(currentId);
  }, [currentId, loadList]);

  const options: SceneOption[] = list
    .filter((s) => s.status === 'ACTIVE')
    .map((s) => ({
      value: s.sceneCode,
      label: `${s.name} (${s.sceneCode})`,
      searchText: `${s.name} ${s.sceneCode}`.toLowerCase(),
    }));

  const handleChange = (code: string | undefined) => {
    if (code) {
      const scene = list.find((s) => s.sceneCode === code);
      setSelectedScene(code, scene?.name);
      navigate(route(ROUTES.SCENE_RULES, { sceneCode: code }));
    } else {
      setSelectedScene(null);
      navigate(ROUTES.SCENES);
    }
  };

  return (
    <Select
      value={selectedSceneCode ?? undefined}
      onChange={handleChange}
      showSearch
      allowClear
      placeholder={t('scene.selector.placeholder')}
      filterOption={(input, option) =>
        (option as unknown as SceneOption)?.searchText?.includes(input.toLowerCase()) ?? false
      }
      options={options}
      style={{ width: 260 }}
      size="small"
    />
  );
}
