package com.astrawms.common.ops;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * A message replayed from a dead-letter queue goes back to its original topic, which every consumer group of that
 * topic reads. It carries {@value #REPLAY_FOR_GROUP} naming the group that dead-lettered it; every other group skips
 * it, because it processed the original successfully (ADR-0018).
 */
public class ReplayRouting implements RecordInterceptor<Object, Object> {

    public static final String REPLAY_FOR_GROUP = "astra-replay-for-group";
    public static final String REPLAYED_BY = "astra-replayed-by";

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        Header target = record.headers().lastHeader(REPLAY_FOR_GROUP);
        if (target == null) {
            return record;
        }
        String group = consumer.groupMetadata().groupId();
        return group.equals(new String(target.value(), StandardCharsets.UTF_8)) ? record : null;
    }
}
