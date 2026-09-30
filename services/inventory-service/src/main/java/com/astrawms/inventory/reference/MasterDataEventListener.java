package com.astrawms.inventory.reference;

import com.astrawms.common.contracts.MasterDataEvents;
import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.common.messaging.EnvelopeCodec;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.InboxGuard;
import com.astrawms.common.tenancy.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Projects Master Data events into the local reference tables used for inventory validation. */
@Component
public class MasterDataEventListener {

    private static final Logger log = LoggerFactory.getLogger(MasterDataEventListener.class);

    private final EnvelopeCodec codec;
    private final InboxGuard inbox;
    private final ReferenceRepository refs;
    private final TransactionTemplate tx;

    public MasterDataEventListener(EnvelopeCodec codec, InboxGuard inbox, ReferenceRepository refs,
                                   TransactionTemplate tx) {
        this.codec = codec;
        this.inbox = inbox;
        this.refs = refs;
        this.tx = tx;
    }

    @KafkaListener(topics = "${astra.topics.masterdata-events:wms.masterdata.events.v1}",
            groupId = "inventory-service.reference")
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = codec.read(record.value());
        TenantContext.runAs(new TenantContext.Scope(envelope.tenantId(), "system:master-data", "EVENT"),
                () -> tx.executeWithoutResult(status -> apply(envelope)));
    }

    private void apply(EventEnvelope envelope) {
        if (!inbox.firstDelivery(envelope.sourceSystem(), envelope.messageId().toString(), envelope.messageType())) {
            log.debug("Duplicate master data message {} ignored", envelope.messageId());
            return;
        }
        switch (envelope.messageType()) {
            case MasterDataEvents.ITEM_UPSERTED -> {
                if (!refs.upsertItem(codec.payload(envelope, ItemUpserted.class))) {
                    log.info("Stale ItemUpserted {} for {} ignored", envelope.messageId(), envelope.businessKey());
                }
            }
            case MasterDataEvents.LOCATION_UPSERTED -> refs.upsertLocation(codec.payload(envelope, LocationUpserted.class));
            default -> log.debug("Master data message type {} not used by inventory", envelope.messageType());
        }
    }
}
