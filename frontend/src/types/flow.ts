/**
 * DECISION_FLOW 决策图类型——对齐后端 kernel flow 模型
 * （com.sstlfsj.rule.kernel.api.model.flow.*）。判别字段 `type` == 后端简单类名。
 * 图只做编排，叶子逻辑由 RuleRefNode 引用的独立规则承载；与 conditionAst / script 平级三选一。
 */

/** flow 节点类型判别值（== 后端 FlowNodeType.tag / 简单类名）。 */
export type FlowNodeType = 'RuleRefNode' | 'SwitchNode' | 'TransformNode' | 'OutputNode';

/** 引用一条已有规则作为叶子；被引规则版本在发布期冻结。 */
export interface RuleRefNode {
  type: 'RuleRefNode';
  id: string;
  ruleCode: string;
}

/** 分支节点：求值 expression，按结果匹配出边 caseKey；无匹配走 default（caseKey=null 的出边）。 */
export interface SwitchNode {
  type: 'SwitchNode';
  id: string;
  /** 表达式引擎标识（== ExpressionLang.name，如 CEL / AVIATOR）。 */
  lang: string;
  expression: string;
  /** 合法分支键集合（出边 caseKey 须属此集）。 */
  caseKeys: string[];
}

/** 变换节点：求值 expression 写入 flow 命名空间的 outputKey，供下游经 flow.{outputKey} 读取。 */
export interface TransformNode {
  type: 'TransformNode';
  id: string;
  lang: string;
  expression: string;
  outputKey: string;
}

/** 终点节点：产出 decisionCode 对应决策，汇入 flow 结果集。 */
export interface OutputNode {
  type: 'OutputNode';
  id: string;
  decisionCode: string;
}

/** 决策图节点——按 type 判别的四种形态联合。 */
export type FlowNode = RuleRefNode | SwitchNode | TransformNode | OutputNode;

/** 有向边：from → to。caseKey 仅 Switch 出边非空；其余边及 default 出边为 null。 */
export interface FlowEdge {
  from: string;
  to: string;
  caseKey: string | null;
}

/** 决策图：节点 + 有向边 + 入口。 */
export interface FlowGraph {
  nodes: FlowNode[];
  edges: FlowEdge[];
  inputNodeId: string;
}
