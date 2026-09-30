package com.astrawms.common.messaging;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Purges messaging bookkeeping (ADR-0002):
 * <ul>
 *   <li>outbox rows published longer ago than the outbox retention (default 7 days, matching Kafka retention);</li>
 *   <li>inbox rows older than the inbox retention (default 30 days). This must exceed the longest possible
 *       redelivery window (topic retention plus DLQ replay), or a replayed message could be applied twice.</li>
 * </ul>
 * Unpublished outbox rows and the per-key sequence table are never purged. Deletes run in small batches.
 */
public class MessagingHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(MessagingHousekeeping.class);
    private static final int BATCH = 5_000;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration outboxRetention;
    private final Duration inboxRetention;

    public MessagingHousekeeping(JdbcClient jdbc, Clock clock, Duration outboxRetention, Duration inboxRetention) {
        if (inboxRetention.compareTo(outboxRetention) < 0) {
            throw new IllegalArgumentException("Inbox retention must not be shorter than outbox retention");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.outboxRetention = outboxRetention;
        this.inboxRetention = inboxRetention;
    }

    public record Purged(int outbox, int inbox) {
    }

    @Scheduled(fixedDelayString = "${astra.housekeeping.interval-ms:3600000}", initialDelayString = "${astra.housekeeping.initial-delay-ms:600000}")
    public void scheduled() {
        try {
            Purged p = purge();
            if (p.outbox() + p.inbox() > 0) {
                log.info("Messaging housekeeping purged {} outbox and {} inbox rows", p.outbox(), p.inbox());
            }
        } catch (RuntimeException e) {
            log.warn("Messaging housekeeping failed; will retry", e);
        }
    }

    public Purged purge() {
        Timestamp outboxCutoff = Timestamp.from(clock.instant().minus(outboxRetention));
        Timestamp inboxCutoff = Timestamp.from(clock.instant().minus(inboxRetention));
        int outbox = 0;
        int n;
        do {
            n = jdbc.sql("""
                            delete from outbox where id in (
                                select id from outbox where published_at is not null and published_at < :cutoff
                                order by id limit :batch)""")
                    .param("cutoff", outboxCutoff).param("batch", BATCH).update();
            outbox += n;
        } while (n == BATCH);
        int inbox = 0;
        do {
            n = jdbc.sql("""
                            delete from inbox where ctid in (
                                select ctid from inbox where processed_at < :cutoff limit :batch)""")
                    .param("cutoff", inboxCutoff).param("batch", BATCH).update();
            inbox += n;
        } while (n == BATCH);
        return new Purged(outbox, inbox);
    }
}
