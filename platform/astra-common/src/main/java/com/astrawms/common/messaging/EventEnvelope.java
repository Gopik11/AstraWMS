package com.astrawms.common.messaging;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Canonical message envelope (scope §D.4.1, ISD-00 §2). Every event and integration message on Kafka uses it.
 *
 * @param sequence monotonic and gap-free per ({@code topic}, {@code businessKey}) for messages produced by
 *                 AstraWMS; consumers use it to detect gaps and stale messages (ISD-00 §5)
 */
public record EventEnvelope(
        UUID messageId,
        String messageType,
        String schemaVersion,
        String sourceSystem,
        String targetSystem,
        String tenantId,
        String siteId,
        String ownerId,
        String businessKey,
        String correlationId,
        long sequence,
        Instant createdAtUtc,
        JsonNode payload) {
}
