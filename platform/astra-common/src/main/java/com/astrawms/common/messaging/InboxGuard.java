package com.astrawms.common.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Consumer-side de-duplication (ISD-00 §5): records {@code (sourceSystem, messageId)} in the same transaction as
 * the message's business effect. A redelivered message finds its row and is skipped.
 */
public class InboxGuard {

    private final JdbcClient jdbc;

    public InboxGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns {@code true} the first time a message is seen; must run inside the processing transaction. */
    public boolean firstDelivery(String sourceSystem, String messageId, String messageType) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("InboxGuard requires an active transaction");
        }
        int inserted = jdbc.sql("""
                        insert into inbox (source_system, message_id, message_type, processed_at)
                        values (:source, :id, :type, now())
                        on conflict (source_system, message_id) do nothing""")
                .param("source", sourceSystem)
                .param("id", messageId)
                .param("type", messageType)
                .update();
        return inserted == 1;
    }
}
