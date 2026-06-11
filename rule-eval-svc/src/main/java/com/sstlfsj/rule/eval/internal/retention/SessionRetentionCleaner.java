package com.sstlfsj.rule.eval.internal.retention;

import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.eval.internal.repository.DryRunSessionMapper;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.function.ToIntFunction;

/** 定时清理超期 session（evaluation_session / dry_run_session），各按自己保留窗，分批短事务、幂等。 */
public class SessionRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(SessionRetentionCleaner.class);

    private final EvaluationSessionMapper evaluationSessionMapper;
    private final DryRunSessionMapper dryRunSessionMapper;
    private final ActionExecutionMapper actionExecutionMapper;
    private final RetentionProperties props;

    public SessionRetentionCleaner(EvaluationSessionMapper evaluationSessionMapper,
                                   DryRunSessionMapper dryRunSessionMapper,
                                   ActionExecutionMapper actionExecutionMapper,
                                   RetentionProperties props) {
        this.evaluationSessionMapper = evaluationSessionMapper;
        this.dryRunSessionMapper = dryRunSessionMapper;
        this.actionExecutionMapper = actionExecutionMapper;
        this.props = props;
    }

    /** 默认每日 03:30；cron 可经 engine.rule.retention.cron 覆盖。 */
    @Scheduled(cron = "${engine.rule.retention.cron:0 30 3 * * *}")
    public void purge() {
        int es = purgeLoop(c -> evaluationSessionMapper.purgeOlderThan(c, props.getBatchSize()),
                LocalDateTime.now().minusDays(props.getEvaluationSessionDays()));
        int dr = purgeLoop(c -> dryRunSessionMapper.purgeOlderThan(c, props.getBatchSize()),
                LocalDateTime.now().minusDays(props.getDryRunSessionDays()));
        int ae = purgeLoop(c -> actionExecutionMapper.purgeOlderThan(c, props.getBatchSize()),
                LocalDateTime.now().minusDays(props.getActionExecutionDays()));
        log.info("retention 清理 session 完成 evaluation_session={} dry_run_session={} action_execution={}", es, dr, ae);
    }

    /** 分批循环删，直到单批不足 batchSize；返回累计删除数。 */
    private int purgeLoop(ToIntFunction<LocalDateTime> del, LocalDateTime cutoff) {
        int total = 0, n;
        do {
            n = del.applyAsInt(cutoff);
            total += n;
        } while (n == props.getBatchSize());
        return total;
    }
}
