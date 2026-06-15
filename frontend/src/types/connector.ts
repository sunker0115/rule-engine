import type { SourceType } from './metric';

/** HTTP 方法（与后端 ConnectorDescriptor.request.method 取值一致） */
export type HttpMethod = 'GET' | 'POST' | 'PUT';

/** 鉴权方案判别值（AuthScheme.kind） */
export type AuthKind = 'STATIC_HEADER' | 'BEARER' | 'OAUTH2_CLIENT_CREDENTIALS';

/** successWhen 比较操作符 */
export type CompareOp = 'EQ' | 'NE' | 'GT' | 'GE' | 'LT' | 'LE';

/** 重试触发条件 */
export type RetryTrigger = 'TIMEOUT' | 'UPSTREAM_5XX';

/** 模板化键值对（query 项 / header 项），value 走变量模板渲染 */
export interface TemplateParam {
  name: string;
  valueTemplate: string;
}

/** 成功判定谓词：对 response 中 path 取值与 value 按 op 比较 */
export interface Predicate {
  path: string;
  op: CompareOp;
  value: unknown;
}

/** 响应映射：成功判定 + 取值路径 */
export interface ResponseMapping {
  successWhen: Predicate;
  valuePath: string;
}

/** HTTP 请求模板 */
export interface HttpRequestTemplate {
  method: HttpMethod;
  pathTemplate: string;
  query?: TemplateParam[];
  headers?: TemplateParam[];
  bodyTemplate?: string;
}

/** 静态请求头鉴权 */
export interface StaticHeaderAuth {
  kind: 'STATIC_HEADER';
  headerName: string;
  credentialRef: string;
}

/** Bearer Token 鉴权 */
export interface BearerAuth {
  kind: 'BEARER';
  tokenRef: string;
}

/** OAuth2 client credentials 鉴权 */
export interface OAuth2ClientCredentialsAuth {
  kind: 'OAUTH2_CLIENT_CREDENTIALS';
  tokenUrl: string;
  clientIdRef: string;
  clientSecretRef: string;
  scopes?: string[];
}

/** 鉴权方案——按 kind 判别的可辨识联合 */
export type AuthScheme = StaticHeaderAuth | BearerAuth | OAuth2ClientCredentialsAuth;

/** 熔断策略 */
export interface CircuitBreakerPolicy {
  failureRateThreshold: number;
  windowSeconds: number;
  openSeconds: number;
}

/** 韧性策略：超时 / 重试 / 熔断 */
export interface ResiliencePolicy {
  connectTimeoutMs: number;
  readTimeoutMs: number;
  retries: number;
  retryOn?: RetryTrigger[];
  circuitBreaker?: CircuitBreakerPolicy;
}

/** errorMapping 匹配条件 */
export interface ErrorMatch {
  statusFrom?: number;
  statusTo?: number;
  envelopeCode?: string;
}

/** 错误映射规则：命中 when 时映射为 to 错误码 */
export interface ErrorRule {
  when: ErrorMatch;
  to: string;
}

/** 连接器描述符——镜像后端 ConnectorDescriptor record，字段名即 JSON 键 */
export interface ConnectorDescriptor {
  endpointRef: string;
  request: HttpRequestTemplate;
  response: ResponseMapping;
  auth?: AuthScheme;
  resilience?: ResiliencePolicy;
  errorMapping?: ErrorRule[];
}

/** 连接器列表项（list 接口返回） */
export interface ConnectorListItem {
  connectorCode: string;
  name: string;
  status?: string;
  descriptor?: ConnectorDescriptor;
  tenantId?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** 取数链路追踪（:test 端点返回，镜像后端 FetchTrace record） */
export interface FetchTrace {
  sourceType: SourceType;
  renderedRequest?: string;
  boundSql?: string;
  rawResponse?: string;
  successMatched?: boolean;
  mappedValue?: unknown;
  errorCode?: string;
}

/** 连接器写请求体（create / update 共用） */
export interface ConnectorWriteBody {
  name: string;
  descriptor: ConnectorDescriptor;
}

/** :test 端点请求体（connector 与 metric 共用） */
export interface FetchTestSample {
  sampleVars?: Record<string, unknown>;
  samplePayload?: Record<string, unknown>;
  sampleSubjectId?: string;
}
