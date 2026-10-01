package com.astrawms.task.projection;

import com.astrawms.common.contracts.InventoryContracts;
import com.astrawms.common.contracts.InventoryContracts.InventoryChanged;
import com.astrawms.common.contracts.MasterDataEvents;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.common.messaging.EnvelopeCodec;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.InboxGuard;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.task.service.TaskService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Keeps projections current and triggers putaway. Projection update and task reaction share one transaction. */
@Component
public class ProjectionListeners {

    private final EnvelopeCodec codec;
    private final InboxGuard inbox;
    private final Projections projections;
    private final TaskService tasks;
    private final TransactionTemplate tx;

    public ProjectionListeners(EnvelopeCodec codec, InboxGuard inbox, Projections projections, TaskService tasks,
                               TransactionTemplate tx) {
        this.codec = codec;
        this.inbox = inbox;
        this.projections = projections;
        this.tasks = tasks;
        this.tx = tx;
    }

    @KafkaListener(topics = MasterDataEvents.TOPIC, groupId = "task-service.reference")
    public void onMasterData(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        process(e, () -> {
            switch (e.messageType()) {
                case MasterDataEvents.ITEM_UPSERTED -> projections.upsertItem(codec.payload(e, ItemUpserted.class));
                case MasterDataEvents.LOCATION_UPSERTED -> projections.upsertLocation(codec.payload(e, LocationUpserted.class));
                default -> { }
            }
        });
    }

    @KafkaListener(topics = InventoryContracts.TOPIC, groupId = "task-service.inventory")
    public void onInventory(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        if (!InventoryChanged.TYPE.equals(e.messageType())) {
            return;
        }
        InventoryChanged change = codec.payload(e, InventoryChanged.class);
        process(e, () -> {
            projections.applyStock(e.siteId(), change);
            tasks.onInventoryChanged(e.siteId(), change);
        });
    }

    @KafkaListener(topics = OutboundContracts.TOPIC_TASK_REQUESTS, groupId = "task-service.requests")
    public void onTaskRequest(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        process(e, () -> {
            switch (e.messageType()) {
                case OutboundContracts.PickRequested.TYPE ->
                        tasks.onPickRequested(e.siteId(), codec.payload(e, OutboundContracts.PickRequested.class));
                case com.astrawms.common.contracts.InventoryContracts.CountRequested.TYPE ->
                        tasks.onCountRequested(e.siteId(), codec.payload(e,
                                com.astrawms.common.contracts.InventoryContracts.CountRequested.class));
                case OutboundContracts.ReturnRequested.TYPE ->
                        tasks.onReturnRequested(e.siteId(), codec.payload(e, OutboundContracts.ReturnRequested.class));
                case OutboundContracts.PickCancelled.TYPE ->
                        tasks.onPickCancelled(e.siteId(), codec.payload(e, OutboundContracts.PickCancelled.class));
                default -> { }
            }
        });
    }

    private void process(EventEnvelope e, Runnable work) {
        TenantContext.runAs(new TenantContext.Scope(e.tenantId(), "system:task-service", "EVENT"),
                () -> tx.executeWithoutResult(s -> {
                    if (inbox.firstDelivery(e.sourceSystem(), e.messageId().toString(), e.messageType())) {
                        work.run();
                    }
                }));
    }
}
