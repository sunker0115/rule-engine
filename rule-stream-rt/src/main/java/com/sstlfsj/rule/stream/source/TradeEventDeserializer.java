package com.sstlfsj.rule.stream.source;

import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 从 Kafka value(JSON bytes) 反序列化为 TradeEvent。 */
public class TradeEventDeserializer implements DeserializationSchema<TradeEvent> {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Override
    public TradeEvent deserialize(byte[] message) {
        try {
            return MAPPER.readValue(message, TradeEvent.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize TradeEvent", e);
        }
    }

    @Override
    public boolean isEndOfStream(TradeEvent nextElement) { return false; }

    @Override
    public TypeInformation<TradeEvent> getProducedType() {
        return TypeInformation.of(TradeEvent.class);
    }
}
