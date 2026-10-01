package com.astrawms.common.messaging;

import io.micrometer.core.instrument.Metrics;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * Platform consumer error policy (ISD-00 §6, architecture §F.8.1):
 * <ul>
 *   <li>transient failures are retried in place with exponential backoff;</li>
 *   <li>poison messages ({@link PoisonMessageException}, unparseable JSON) and messages whose retries are exhausted
 *       are published to {@code <topic>.dlq} with the original key, value and headers plus Spring Kafka's
 *       {@code kafka_dlt-*} diagnostic headers (exception, original topic/partition/offset);</li>
 *   <li>the offset is committed only after the record is either processed or dead-lettered, so nothing is lost.</li>
 * </ul>
 */
public final class KafkaErrorHandling {

    public static final String DLQ_SUFFIX = ".dlq";
    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandling.class);

    private KafkaErrorHandling() {
    }

    public static DefaultErrorHandler deadLetteringErrorHandler(KafkaTemplate<String, String> template,
                                                                long initialIntervalMs, long maxIntervalMs,
                                                                long maxElapsedMs) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, e) -> {
            log.error("Dead-lettering {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), rootMessage(e));
            Metrics.counter("astra.kafka.dead.lettered", "topic", record.topic()).increment();
            return new TopicPartition(record.topic() + DLQ_SUFFIX, -1);
        });
        ExponentialBackOff backOff = new ExponentialBackOff(initialIntervalMs, 2.0);
        backOff.setMaxInterval(maxIntervalMs);
        backOff.setMaxElapsedTime(maxElapsedMs);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(PoisonMessageException.class, JacksonException.class);
        return handler;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
