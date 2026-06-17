package com.sstlfsj.rule.eval.internal.dispatch;

import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * PUSH 模式异步派发器：LinkedBlockingQueue + 虚拟线程消费者，替代裸 CompletableFuture。
 * 队列满时 submit 返回 false（背压信号），不阻塞调用方。
 */
public class PushEventDispatcher {

    private final int capacity;
    private final Consumer<RuleEvent> evaluateFn;
    private LinkedBlockingQueue<RuleEvent> queue;
    private volatile boolean running = false;
    private Thread consumerThread;

    public PushEventDispatcher(int capacity, Consumer<RuleEvent> evaluateFn) {
        this.capacity = capacity;
        this.evaluateFn = evaluateFn;
    }

    /** 启动消费线程（由 EvalServiceImpl.afterPropertiesSet 调用）。 */
    public void start() {
        queue = new LinkedBlockingQueue<>(capacity);
        running = true;
        consumerThread = Thread.ofVirtual().name("push-dispatcher").start(this::consumeLoop);
    }

    /**
     * 投递 PUSH 事件（非阻塞）。
     * @return true 表示接受；false 表示队列满，主动拒绝
     */
    public boolean submit(RuleEvent event) {
        return queue.offer(event);
    }

    /** trace 队列当前积压数（Gauge 注册用）。 */
    public int queueSize() { return queue != null ? queue.size() : 0; }

    /** trace 队列最大容量（Gauge 注册用）。 */
    public int queueCapacity() { return capacity; }

    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            try {
                RuleEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    evaluateFn.accept(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** 优雅停止：先置 running=false，让消费循环把队列排空后自然退出，再 join；超时才中断兜底，避免停机丢未消费事件。 */
    public void stop() {
        running = false;
        if (consumerThread != null) {
            try {
                // consumeLoop 在 running=false 后仍 while(!queue.isEmpty()) 处理完剩余事件再退出
                consumerThread.join(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            consumerThread.interrupt();
        }
    }
}
