package com.astrawms.common.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Consumer-side de-duplication (ISD-00 §5): records {@code (sourceSystem, messageId, consumerGroup)} in the same
 * transaction as the message's business effect. A redelivered message finds its row and is skipped.
 *
 * <p>The consumer group is part of the key (ADR-0029): two groups of one service that consume the same topic (the SAP
 * adapter's postings and its webhooks) each process a message once. Rows written before ADR-0029 have the group
 * {@code ''} and still count as processed for every group until housekeeping purges them. Listeners do not call this
 * directly: they use {@link InboxProcessing}.
 */
public class InboxGuard {

    /** The consumer group of inbox rows written before ADR-0029. */
    public static final String LEGACY_GROUP = "";

    private final JdbcClient jdbc;

    public InboxGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns {@code true} the first time this group sees a message; must run inside the processing transaction. */
    public boolean firstDelivery(String consumerGroup, String sourceSystem, String messageId, String messageType) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("InboxGuard requires an active transaction");
        }
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("InboxGuard needs the consumer group");
        }
        int inserted = jdbc.sql("""
                        insert into inbox (source_system, message_id, consumer_group, message_type, processed_at)
                        select :source, :id, :group, :type, now()
                        where not exists (select 1 from inbox
                                          where source_system = :source and message_id = :id and consumer_group = '')
                        on conflict (source_system, message_id, consumer_group) do nothing""")
                .param("source", sourceSystem)
                .param("id", messageId)
                .param("group", consumerGroup)
                .param("type", messageType)
                .update();
        return inserted == 1;
    }

    /** Whether a group has processed a message (DLQ replay checks this before replaying). */
    public boolean processed(String consumerGroup, String sourceSystem, String messageId) {
        return jdbc.sql("""
                        select exists (select 1 from inbox where source_system = :source and message_id = :id
                                       and consumer_group in (:group, ''))""")
                .param("source", sourceSystem).param("id", messageId).param("group", consumerGroup)
                .query(Boolean.class).single();
    }
}
