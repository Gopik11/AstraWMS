package com.astrawms.outbound.integration;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ErpPostingResult;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.contracts.OutboundContracts.OutboundOrder;
import com.astrawms.common.contracts.OutboundContracts.ShipmentConfirmation;
import com.astrawms.common.contracts.OutboundContracts.TaskCompleted;
import com.astrawms.common.messaging.EnvelopeCodec;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.InboxProcessing;
import com.astrawms.outbound.service.OutboundService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Orders from the ERP adapters, pick completions from the task service, and ERP posting results. */
@Component
public class OutboundListeners {

    private final EnvelopeCodec codec;
    private final InboxProcessing inbox;
    private final OutboundService outbound;

    public OutboundListeners(EnvelopeCodec codec, InboxProcessing inbox, OutboundService outbound) {
        this.codec = codec;
        this.inbox = inbox;
        this.outbound = outbound;
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

    /**
     * Backorder recovery (ADR-0019): available stock of an item increased somewhere other than outbound staging.
     * Inventory decides what is allocable (staging, receiving and QC stock is not), so a receipt at the dock recovers
     * nothing until it is put away.
     */
    @KafkaListener(topics = com.astrawms.common.contracts.InventoryContracts.TOPIC, groupId = "outbound-service.inventory")
    public void onInventory(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        if (!com.astrawms.common.contracts.InventoryContracts.InventoryChanged.TYPE.equals(e.messageType())) {
            return;
        }
        var change = codec.payload(e, com.astrawms.common.contracts.InventoryContracts.InventoryChanged.class);
        change.lines().stream().filter(l -> l.qtyDelta().signum() > 0
                        && "AVAILABLE".equals(l.status()) && !l.txnType().startsWith("PICK"))
                .findFirst()
                .ifPresent(l -> process(e, "EVENT", () -> outbound.onStockAvailable(e.siteId(), change.ownerId(),
                        change.itemNo(), new com.astrawms.outbound.service.OutboundService.StockArrival(l.txnType(),
                                l.locationId(), l.lpnId(), change.operationId()))));
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
        inbox.once(e, "system:" + e.sourceSystem(), channel, work);
    }
}
