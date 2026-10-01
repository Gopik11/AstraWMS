package com.astrawms.outbound.integration;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ErpPostingResult;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.contracts.OutboundContracts.OutboundOrder;
import com.astrawms.common.contracts.OutboundContracts.ShipmentConfirmation;
import com.astrawms.common.contracts.OutboundContracts.TaskCompleted;
import com.astrawms.common.messaging.EnvelopeCodec;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.InboxGuard;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.outbound.service.OutboundService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Orders from the ERP adapters, pick completions from the task service, and ERP posting results. */
@Component
public class OutboundListeners {

    private final EnvelopeCodec codec;
    private final InboxGuard inbox;
    private final OutboundService outbound;
    private final TransactionTemplate tx;

    public OutboundListeners(EnvelopeCodec codec, InboxGuard inbox, OutboundService outbound, TransactionTemplate tx) {
        this.codec = codec;
        this.inbox = inbox;
        this.outbound = outbound;
        this.tx = tx;
    }

    @KafkaListener(topics = OutboundContracts.TOPIC_OUTBOUND_ORDERS, groupId = "outbound-service.orders")
    public void onOrder(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        process(e, "ERP", () -> outbound.onOrder(e, codec.payload(e, OutboundOrder.class)));
    }

    @KafkaListener(topics = OutboundContracts.TOPIC_TASK_EVENTS, groupId = "outbound-service.tasks")
    public void onTaskEvent(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        if (TaskCompleted.TYPE.equals(e.messageType())) {
            process(e, "EVENT", () -> outbound.onTaskCompleted(codec.payload(e, TaskCompleted.class)));
        }
    }

    @KafkaListener(topics = IntegrationContracts.TOPIC_ERP_POSTING_RESULTS, groupId = "outbound-service.posting-results")
    public void onPostingResult(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        ErpPostingResult r = codec.payload(e, ErpPostingResult.class);
        if (ShipmentConfirmation.TYPE.equals(r.sourceMessageType())) {
            process(e, "ERP", () -> outbound.onPostingResult(r));
        }
    }

    private void process(EventEnvelope e, String channel, Runnable work) {
        TenantContext.runAs(new TenantContext.Scope(e.tenantId(), "system:" + e.sourceSystem(), channel),
                () -> tx.executeWithoutResult(s -> {
                    if (inbox.firstDelivery(e.sourceSystem(), e.messageId().toString(), e.messageType())) {
                        work.run();
                    }
                }));
    }
}
