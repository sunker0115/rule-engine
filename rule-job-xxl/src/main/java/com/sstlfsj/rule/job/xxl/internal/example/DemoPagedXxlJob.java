package com.sstlfsj.rule.job.xxl.internal.example;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页 Job 的 xxl-job 原生写法演示（仅 {@code xxl-demo} profile，需 executor 启用）。
 *
 * <p>对照 rule-job-svc 的 {@code DemoFraudJob#recentLoginUsersPaged}（{@code @RuleJob} 注解式，
 * 仿 ElasticJob DataflowJob：框架按 page 0、1、2… 反复回调拉到空批为止，分页由框架驱动）——
 * xxl-job 没有 DataflowJob 那种"返回一页、框架再要下一页"的回调模型，分页改由 <b>handler 内部自己循环</b>：
 * 一个 {@code @XxlJob} 方法里 while 翻页、每页处理完再取下一页，拉到空批跳出。
 *
 * <p>另一处差异：日志走 {@link XxlJobHelper#log}（落到 xxl-admin 的「调度日志」视图，可在控制台回看），
 * 任务参数走 {@link XxlJobHelper#getJobParam()} 静态取，结果走 {@link XxlJobHelper#handleSuccess}/
 * {@code handleFail}（不写则默认成功）。本例不接 JobRunner/规则评估（那在 rule-job-svc，本模块不依赖），
 * 每个 subject 仅占位处理，真实接入以 {@code XxlJobSchedulerAdapter} 注册的闭包为准。
 */
@Component
@Profile("xxl-demo")
public class DemoPagedXxlJob {

    private static final Logger log = LoggerFactory.getLogger(DemoPagedXxlJob.class);

    /** 演示用每页条数（真实场景作 SQL {@code LIMIT pageSize OFFSET pageNumber*pageSize}）。 */
    private static final int PAGE_SIZE = 2;

    /**
     * 分页扫描近期登录用户（xxl-job 原生 handler 写法，内部自驱分页）。
     *
     * <p>handlerName = {@code demo-paged-xxl}，需在 xxl-admin 建对应 jobinfo（executorHandler 一致）
     * 并配 cron 触发。{@link XxlJobHelper#getJobParam()} 可携带过滤条件（如起始日期），缺省忽略。
     */
    @XxlJob("demo-paged-xxl")
    public void scanRecentLoginUsersPaged() {
        String param = XxlJobHelper.getJobParam();
        XxlJobHelper.log("demo-paged-xxl 开始，param={}", param);
        try {
            int total = scanAllPages();
            XxlJobHelper.log("demo-paged-xxl 完成，共处理 {} 条", total);
            XxlJobHelper.handleSuccess("processed=" + total);
        } catch (RuntimeException e) {
            XxlJobHelper.log(e);
            XxlJobHelper.handleFail("demo-paged-xxl 失败: " + e.getMessage());
        }
    }

    /**
     * 自驱分页扫描全部主体：page 0、1、2… 逐页取数处理，拉到空批为止，返回处理总数。
     * 抽为纯方法（不依赖 XxlJobHelper 运行期上下文），便于单测分页终止逻辑。
     *
     * @return 处理的主体总数
     */
    int scanAllPages() {
        int pageNumber = 0;
        int total = 0;
        while (true) {
            List<String> subjectIds = fetchPage(pageNumber, PAGE_SIZE);
            if (subjectIds.isEmpty()) {
                break;   // 空批 → 已无更多页，结束翻页
            }
            subjectIds.forEach(this::process);
            total += subjectIds.size();
            pageNumber++;
        }
        return total;
    }

    /**
     * 取一页主体（演示用假数据：每页 {@value #PAGE_SIZE} 条，page≥3 返空批以停止翻页）。
     * 真实场景：查 login 日志表 {@code SELECT ... LIMIT pageSize OFFSET pageNumber*pageSize}。
     *
     * @param pageNumber 页码（从 0 起）
     * @param pageSize   每页条数
     * @return 当前页 subjectId 列表，空列表表示已无更多页
     */
    private List<String> fetchPage(int pageNumber, int pageSize) {
        if (pageNumber >= 3) {
            return List.of();
        }
        List<String> page = new ArrayList<>(pageSize);
        for (int i = 0; i < pageSize; i++) {
            page.add("paged-user-" + pageNumber + "-" + i);
        }
        return page;
    }

    /** 占位处理单个主体。真实接入：合成 RuleEvent 注入评估链路（经 XxlJobSchedulerAdapter 注册的闭包 → JobRunner）。 */
    private void process(String subjectId) {
        log.debug("处理主体 subjectId={}", subjectId);
    }
}
