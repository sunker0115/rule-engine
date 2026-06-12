package com.sstlfsj.rule.expression.qlexpress;

import com.ql.util.express.InstructionSet;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;

import java.util.Set;

/** QLExpress 编译产物:持有编译后的 InstructionSet,供运行期执行与发布期依赖抽取。 */
public final class QLExpressCompiledExpression implements CompiledExpression {

    private final InstructionSet instructionSet;
    private final Set<String> referencedVariables;

    /**
     * @param instructionSet      QLExpress 编译后的指令集
     * @param referencedVariables 引用的变量点路径(如 "metrics.txn_cnt_1d"),发布期冻依赖用
     */
    public QLExpressCompiledExpression(InstructionSet instructionSet, Set<String> referencedVariables) {
        this.instructionSet = instructionSet;
        this.referencedVariables = Set.copyOf(referencedVariables);
    }

    /** @return QLExpress InstructionSet(供 QLExpressEngine.evaluate 执行) */
    public InstructionSet instructionSet() {
        return instructionSet;
    }

    @Override
    public Set<String> referencedVariables() {
        return referencedVariables;
    }
}
