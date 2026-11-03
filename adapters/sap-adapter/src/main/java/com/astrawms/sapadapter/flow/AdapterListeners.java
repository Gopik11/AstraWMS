package com.astrawms.sapadapter.flow;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ApplicationAck;
import com.astrawms.common.contracts.IntegrationContracts.GoodsMovement;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptConfirmation;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.messaging.EnvelopeCodec;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.InboxProcessing;
import com.astrawms.sapadapter.config.SapProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka entry points of the adapter. Each message is processed in one transaction together with its inbox record;
 * a transient SAP failure rolls both back and the listener's error handler redelivers the message.
 */
@Component
public class AdapterListeners {

    private final EnvelopeCodec codec;
    private final InboxProcessing inbox;
    private final PostingFlows postings;
    private final InboundDeliveryFlow deliveries;
    private final SapProperties sap;

    public AdapterListeners(EnvelopeCodec codec, InboxProcessing inbox, PostingFlows postings,
                            InboundDeliveryFlow deliveries, SapProperties sap) {
        this.codec = codec;
        this.inbox = inbox;
        this.postings = postings;
        this.deliveries = deliveries;
        this.sap = sap;
    }

    @KafkaListener(topics = IntegrationContracts.TOPIC_RECEIPT_CONFIRMATIONS, groupId = "sap-adapter.receipt-confirmations")
    public void onReceiptConfirmation(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        process(e, () -> postings.confirmReceipt(e, codec.payload(e, ReceiptConfirmation.class)));
    }

    @KafkaListener(topics = OutboundContracts.TOPIC_SHIPMENT_CONFIRMATIONS, groupId = "sap-adapter.shipment-confirmations")
    public void onShipmentConfirmation(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        process(e, () -> postings.confirmShipment(e, codec.payload(e, OutboundContracts.ShipmentConfirmation.class)));
    }

    @KafkaListener(topics = IntegrationContracts.TOPIC_GOODS_MOVEMENTS, groupId = "sap-adapter.goods-movements")
    public void onGoodsMovement(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        process(e, () -> postings.postGoodsMovement(e, codec.payload(e, GoodsMovement.class)));
    }

    @KafkaListener(topics = com.astrawms.common.contracts.ReturnsContracts.TOPIC_RETURN_CONFIRMATIONS,
                   groupId = "sap-adapter.return-confirmations")
    public void onReturnConfirmation(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        process(e, () -> postings.confirmReturn(e,
                codec.payload(e, com.astrawms.common.contracts.ReturnsContracts.ReturnConfirmation.class)));
    }

    @KafkaListener(topics = IntegrationContracts.TOPIC_APPLICATION_ACKS, groupId = "sap-adapter.acks")
    public void onAck(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        ApplicationAck ack = codec.payload(e, ApplicationAck.class);
        if (sap.logicalSystem().equals(ack.sourceSystem())) {
            process(e, () -> deliveries.onAck(ack));
        }
    }

    private void process(EventEnvelope e, Runnable work) {
        inbox.once(e, "system:sap-adapter", "INTEGRATION", work);
    }
}
