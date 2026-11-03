package com.astrawms.inventory.reference;

import com.astrawms.common.contracts.MasterDataEvents;
import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.contracts.MasterDataEvents.LocationUpserted;
import com.astrawms.common.messaging.EnvelopeCodec;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.InboxProcessing;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Projects Master Data events into the local reference tables used for inventory validation. */
@Component
public class MasterDataEventListener {

    private static final Logger log = LoggerFactory.getLogger(MasterDataEventListener.class);

    private final EnvelopeCodec codec;
    private final InboxProcessing inbox;
    private final ReferenceRepository refs;

    public MasterDataEventListener(EnvelopeCodec codec, InboxProcessing inbox, ReferenceRepository refs) {
        this.codec = codec;
        this.inbox = inbox;
        this.refs = refs;
    }

    @KafkaListener(topics = "${astra.topics.masterdata-events:wms.masterdata.events.v1}",
            groupId = "inventory-service.reference")
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = codec.read(record.value());
        inbox.once(envelope, "system:master-data", "EVENT", () -> apply(envelope),
                dup -> log.debug("Duplicate master data message {} ignored", dup.messageId()));
    }

    private void apply(EventEnvelope envelope) {
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
