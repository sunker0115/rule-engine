package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * VirtualThread 并行评估器：共享同一 EvalContext（不可变，只读），零锁。
 * 用 {@code Executors.newVirtualThreadPerTaskExecutor()} 为每条候选规则起一条虚拟线程，
 * 汇聚逻辑复用 {@link EvalEngine#evaluateAllCandidates} 的语义。
 */
final class ParallelEvaluator {

    private ParallelEvaluator() {}

    /**
     * ALL_HITS / HIGHEST_PRIORITY：全量并行执行所有候选规则，收集全部结果后合成决策。
     *
     * @param passed      通过 pre-gate 的候选快照
     * @param ctx         不可变评估上下文（共享引用，只读）
     * @param executorFn  按快照选择 RuleVersionExecutor
     * @return 合成的评估结果
     */
    static EvalResult evaluateAllParallel(
            List<RuleVersionSnapshot> passed, EvalContext ctx,
            Function<RuleVersionSnapshot, RuleVersionExecutor> executorFn) {

        List<Future<EvalResult>> futures;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = passed.stream()
                    .map(snap -> executor.submit(() -> executorFn.apply(snap).execute(snap, ctx)))
                    .toList();
        }

        // executor 已 close → 所有虚拟线程已 join，直接 get
        return mergeResults(futures);
    }

    /**
     * FIRST_HIT 批式并行：一批 N 条并行跑，取最高 priority 命中者；全不中跑下一批。
     *
     * @param sorted    已按 FIRST_HIT_ORDER 排好（EvalEngine 负责排序）
     * @param ctx       不可变评估上下文
     * @param executorFn 按快照选择 executor
     * @param batchSize  每批并行条数（全量一批 = sorted.size()）
     * @return 命中结果；全不中返回 EvalResult.miss()
     */
    static EvalResult evaluateFirstHitBatched(
            List<RuleVersionSnapshot> sorted, EvalContext ctx,
            Function<RuleVersionSnapshot, RuleVersionExecutor> executorFn,
            int batchSize) {
        for (int i = 0; i < sorted.size(); i += batchSize) {
            var batch = sorted.subList(i, Math.min(i + batchSize, sorted.size()));
            List<Future<EvalResult>> futures;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                futures = batch.stream()
                        .map(snap -> executor.submit(() -> executorFn.apply(snap).execute(snap, ctx)))
                        .toList();
            }
            EvalResult best = futures.stream()
                    .map(ParallelEvaluator::getQuietly)
                    .filter(r -> r.ruleHit() && r.errorCode() == null)
                    .max(Comparator.comparingInt(r -> r.finalDecision() != null
                            ? r.finalDecision().priority() : 0))
                    .orElse(null);
            if (best != null) return best;
        }
        return EvalResult.miss();
    }

    /**
     * 汇聚多条并行结果：复用现有 evaluateAllCandidates 语义——
     * 收集全部 hitDecisions + allTraces + 首个 errorCode + max aggregatedScore。
     */
    private static EvalResult mergeResults(List<Future<EvalResult>> futures) {
        List<Decision> hitDecisions = new ArrayList<>();
        List<NodeTrace> allTraces = new ArrayList<>();
        String errorCode = null;
        Double aggregatedScore = null;

        for (Future<EvalResult> f : futures) {
            EvalResult r;
            try {
                r = f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (errorCode == null) errorCode = EvalErrorCode.CONDITION_EVAL_ERROR.name();
                continue;
            } catch (ExecutionException e) {
                if (errorCode == null) errorCode = EvalErrorCode.CONDITION_EVAL_ERROR.name();
                continue;
            }
            allTraces.addAll(r.nodeTrace());
            if (r.ruleHit()) {
                hitDecisions.addAll(r.hitDecisions());
            }
            if (r.errorCode() != null && errorCode == null) errorCode = r.errorCode();
            if (r.score() != null) {
                aggregatedScore = aggregatedScore == null ? r.score()
                        : Math.max(aggregatedScore, r.score());
            }
        }

        Decision finalDecision = hitDecisions.isEmpty() ? null
                : Collections.max(hitDecisions, EvalEngine.DECISION_PRECEDENCE);

        return new EvalResult(
                !hitDecisions.isEmpty(),
                finalDecision,
                Collections.unmodifiableList(hitDecisions),
                Collections.unmodifiableList(allTraces),
                errorCode,
                aggregatedScore,
                finalDecision != null ? finalDecision.category() : null,
                null
        );
    }

    private static EvalResult getQuietly(Future<EvalResult> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EvalResult.miss();
        } catch (ExecutionException e) {
            return EvalResult.miss();
        }
    }
}
