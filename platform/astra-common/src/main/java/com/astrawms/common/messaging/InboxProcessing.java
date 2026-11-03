package com.astrawms.common.messaging;

import com.astrawms.common.tenancy.TenantContext;
import java.util.function.Consumer;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one way a Kafka listener applies a message (ADR-0029): in the message's tenant, in one transaction with its
 * inbox record, once per consumer group. Every {@code @KafkaListener} goes through it; {@code scripts/check-listeners.sh}
 * fails the build when one does not.
 */
public class InboxProcessing {

    private final InboxGuard inbox;
    private final TransactionTemplate tx;

    public InboxProcessing(InboxGuard inbox, TransactionTemplate tx) {
        this.inbox = inbox;
        this.tx = tx;
    }

    /**
     * Runs {@code work} unless this listener's consumer group already processed the message.
     *
     * @param actor   the acting user recorded on changes, e.g. {@code system:task-service}
     * @param channel the channel recorded on changes, e.g. {@code EVENT} or {@code ERP}
     * @return whether the work ran (false for a duplicate)
     */
    public boolean once(EventEnvelope e, String actor, String channel, Runnable work) {
        return once(currentGroup(), e, actor, channel, work);
    }

    /** As {@link #once(EventEnvelope, String, String, Runnable)} for an explicitly named consumer group. */
    public boolean once(String consumerGroup, EventEnvelope e, String actor, String channel, Runnable work) {
        boolean[] ran = {false};
        TenantContext.runAs(new TenantContext.Scope(e.tenantId(), actor, channel),
                () -> tx.executeWithoutResult(s -> {
                    if (inbox.firstDelivery(consumerGroup, e.sourceSystem(), e.messageId().toString(), e.messageType())) {
                        work.run();
                        ran[0] = true;
                    }
                }));
        return ran[0];
    }

    /** As {@link #once(EventEnvelope, String, String, Runnable)}, with a callback for duplicates (logging). */
    public boolean once(EventEnvelope e, String actor, String channel, Runnable work, Consumer<EventEnvelope> onDuplicate) {
        boolean ran = once(e, actor, channel, work);
        if (!ran) {
            onDuplicate.accept(e);
        }
        return ran;
    }

    /** The consumer group of the listener invocation on this thread. */
    static String currentGroup() {
        String group = KafkaUtils.getConsumerGroupId();
        if (group == null || group.isBlank()) {
            throw new IllegalStateException("InboxProcessing.once outside a Kafka listener: name the consumer group");
        }
        return group;
    }
}
