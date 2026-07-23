import apiClient from './client';
import { ENDPOINTS } from '@/constants/api-endpoints';

export interface ValidateExpressionResponse {
  valid: boolean;
  /** valid=false 时的人类可读错误信息（含行号列号），valid=true 时为 null */
  error: string | null;
}

export async function validateExpression(
  tenantId: number,
  sceneCode: string,
  lang: string,
  source: string,
): Promise<ValidateExpressionResponse> {
  const { data } = await apiClient.post(ENDPOINTS.EXPRESSION_VALIDATE, {
    tenantId, sceneCode, lang, source,
  });
  return data.data;
}
