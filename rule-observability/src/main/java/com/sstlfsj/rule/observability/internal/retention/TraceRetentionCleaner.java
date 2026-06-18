package com.sstlfsj.rule.observability.internal.retention;

import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.function.ToIntFunction;

/** 定时清理超期 trace（node_trace），按保留窗分批短事务、幂等。 */
public class TraceRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(TraceRetentionCleaner.class);

    private final NodeTraceMapper nodeTraceMapper;
    private final RetentionProperties props;

    public TraceRetentionCleaner(NodeTraceMapper nodeTraceMapper,
                                 RetentionProperties props) {
        this.nodeTraceMapper = nodeTraceMapper;
        this.props = props;
    }

    /** 默认每日 03:30；cron 可经 engine.rule.retention.cron 覆盖。 */
    @Scheduled(cron = "${engine.rule.retention.cron:0 30 3 * * *}")
    public void purge() {
        int nt = purgeLoop(c -> nodeTraceMapper.purgeOlderThan(c, props.getBatchSize()),
                LocalDateTime.now().minusDays(props.getNodeTraceDays()));
        log.info("retention 清理 trace 完成 node_trace={}", nt);
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
