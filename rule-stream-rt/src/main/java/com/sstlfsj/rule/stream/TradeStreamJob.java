package com.sstlfsj.rule.stream;

import com.sstlfsj.rule.stream.feature.*;
import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import com.sstlfsj.rule.stream.model.PartialFeature;
import com.sstlfsj.rule.stream.model.SecondCount;
import com.sstlfsj.rule.stream.model.TradeEvent;
import com.sstlfsj.rule.stream.sink.RedisFeatureSink;
import com.sstlfsj.rule.stream.source.TradeEventDeserializer;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;

import java.time.Duration;

/** Flink 流式风控特征 pipeline——1s 微桶滚动 RT-M + RT-D + RT-B → union → merger → Redis。 */
public class TradeStreamJob {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);                              // 本地 e2e 简化：单并行度避免空分区水印停滞
        env.enableCheckpointing(60_000);   // at-least-once；覆盖写下重复无副作用

        String brokers = getEnv("KAFKA_BROKERS", "localhost:9092");
        String topic = getEnv("KAFKA_TOPIC", "rt.trade.raw");
        String offsetMode = getEnv("KAFKA_OFFSET", "latest");   // e2e 用 earliest
        String redisHost = getEnv("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(getEnv("REDIS_PORT", "6379"));

        KafkaSource<TradeEvent> source = KafkaSource.<TradeEvent>builder()
                .setBootstrapServers(brokers)
                .setTopics(topic)
                .setGroupId("rule-stream-rt")
                .setStartingOffsets("earliest".equals(offsetMode)
                        ? OffsetsInitializer.earliest() : OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new TradeEventDeserializer())
                .build();

        WatermarkStrategy<TradeEvent> watermark = WatermarkStrategy
                .<TradeEvent>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                .withTimestampAssigner((e, ts) -> e.occurredAt().toEpochMilli())
                .withIdleness(Duration.ofSeconds(10));   // e2e 场景下空分区快速让位

        DataStream<TradeEvent> trades = env.fromSource(source, watermark, "kafka-trades");
        KeyedStream<TradeEvent, String> keyed = trades.keyBy(TradeEvent::customerId);

        // RT-M：1s tumbling 每秒计数 → RollingCountFn 环形缓冲滚动求 6 个 size
        DataStream<PartialFeature> rtm = keyed
                .window(TumblingEventTimeWindows.of(Duration.ofSeconds(1)))
                .aggregate(new PerSecondCountFn(), new SecondCountTagFn())
                .keyBy((SecondCount sc) -> sc.customerId)
                .process(new RollingCountFn());

        // RT-D：UTC 自然日累计金额，每笔交易即 emit 当前累计（日内实时）
        DataStream<PartialFeature> rtd = keyed.process(new DailyAmountFn());

        // RT-B：5min/30s 滑动 API 通道占比
        DataStream<PartialFeature> rtb = keyed
                .window(SlidingEventTimeWindows.of(Duration.ofMinutes(5), Duration.ofSeconds(30)))
                .process(new RtbProcessFn());

        rtm.union(rtd, rtb)
                .keyBy((PartialFeature p) -> p.customerId)
                .process(new FeatureSnapshotMerger())
                .name("feature-snapshot-merger")
                .sinkTo(new RedisFeatureSink(redisHost, redisPort))
                .name("redis-sink");

        env.execute("rule-stream-rt feature pipeline");
    }

    private static String getEnv(String key, String def) {
        String v = System.getenv(key);
        return v != null ? v : def;
    }
}
