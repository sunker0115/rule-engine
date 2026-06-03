package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** BlockTransactionHandler 单元测试：v1 stub 直接返回 SUCCESS。 */
class BlockTransactionHandlerTest {

    private final BlockTransactionHandler handler = new BlockTransactionHandler();

    @Test
    void execute_returnsSuccess_withCorrectActionIdAndType() {
        ActionContext ctx = new ActionContext(
                "action-1", "BLOCK_TRANSACTION", Map.of(), null, null, null);

        ActionResult result = handler.execute(ctx);

        assertThat(result.status()).isEqualTo(ActionResult.ActionStatus.SUCCESS);
        assertThat(result.actionId()).isEqualTo("action-1");
        assertThat(result.actionType()).isEqualTo("BLOCK_TRANSACTION");
    }
}
