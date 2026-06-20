package com.sstlfsj.rule.stream.sink;

import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.api.connector.sink2.WriterInitContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;

/** Flink Sink V2：HSET rt:feat:{customerId} 全字段 + EXPIRE。 */
public class RedisFeatureSink implements Sink<FeatureSnapshot> {

    private static final int TTL_SECONDS = 604875;   // 7d+1h
    private final String host;
    private final int port;

    public RedisFeatureSink(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public SinkWriter<FeatureSnapshot> createWriter(WriterInitContext context) {
        return new RedisWriter(host, port);
    }

    /** 运行时创建（不参与序列化），管 JedisPool 生命周期。 */
    static final class RedisWriter implements SinkWriter<FeatureSnapshot> {
        private static final Logger log = LoggerFactory.getLogger(RedisWriter.class);
        private final JedisPool pool;

        RedisWriter(String host, int port) {
            JedisPoolConfig cfg = new JedisPoolConfig();
            cfg.setMaxTotal(8);
            this.pool = new JedisPool(cfg, host, port);
        }

        @Override
        public void write(FeatureSnapshot snap, Context context) {
            try (Jedis jedis = pool.getResource()) {
                String key = "rt:feat:" + snap.customerId;
                Pipeline pipe = jedis.pipelined();
                pipe.hset(key, "rtm_mwr_1s", String.valueOf(snap.rtmMwr1s));
                pipe.hset(key, "rtm_mwr_10s", String.valueOf(snap.rtmMwr10s));
                pipe.hset(key, "rtm_mwr_30s", String.valueOf(snap.rtmMwr30s));
                pipe.hset(key, "rtm_mwr_1m", String.valueOf(snap.rtmMwr1m));
                pipe.hset(key, "rtm_mwr_2m", String.valueOf(snap.rtmMwr2m));
                pipe.hset(key, "rtm_mwr_5m", String.valueOf(snap.rtmMwr5m));
                pipe.hset(key, "rtd_amount_sum", String.valueOf(snap.rtdAmountSum));
                pipe.hset(key, "fast_trade_ratio", String.valueOf(snap.fastTradeRatio));
                pipe.hset(key, "sus_score", String.valueOf(snap.susScore));
                pipe.hset(key, "rt_state", snap.rtState != null ? snap.rtState : "RT_CLEAN");
                pipe.hset(key, "updated_at", String.valueOf(snap.updatedAt));
                pipe.expire(key, TTL_SECONDS);
                pipe.sync();
            } catch (Exception e) {
                // Redis 短暂不可用不让 job 崩溃——checkpoint 重放 + 覆盖写可恢复
                log.error("Redis HSET failed for customer={}, recover on checkpoint replay", snap.customerId, e);
            }
        }

        @Override
        public void flush(boolean endOfInput) {}

        @Override
        public void close() {
            pool.close();
        }
    }
}
