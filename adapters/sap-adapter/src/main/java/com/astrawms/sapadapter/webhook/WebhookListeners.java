package com.astrawms.sapadapter.webhook;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.GoodsMovement;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptConfirmation;
import com.astrawms.common.contracts.OutboundContracts;
import com.astrawms.common.contracts.OutboundContracts.ShipmentConfirmation;
import com.astrawms.common.messaging.EnvelopeCodec;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.InboxProcessing;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns WMS events into webhook deliveries (ADR-0025). Its own consumer groups: a webhook never delays or blocks the
 * SAP postings of the same events. Each group has its own inbox record (ADR-0029), and a delivery is queued once per
 * subscription and message id.
 */
@Component
public class WebhookListeners {

    /** Reason codes of count and physical inventory differences. */
    static final List<String> COUNT_REASONS = List.of("CC_TOL", "CC_VAR", "PI_DIFF");

    private final EnvelopeCodec codec;
    private final Webhooks webhooks;
    private final InboxProcessing inbox;

    public WebhookListeners(EnvelopeCodec codec, Webhooks webhooks, InboxProcessing inbox) {
        this.codec = codec;
        this.webhooks = webhooks;
        this.inbox = inbox;
    }

    @KafkaListener(topics = OutboundContracts.TOPIC_SHIPMENT_CONFIRMATIONS, groupId = "webhooks.shipment-confirmations")
    public void onShipment(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        ShipmentConfirmation s = codec.payload(e, ShipmentConfirmation.class);
        if (s.transferToSiteId() == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transferNo", s.erpDocNo());
        data.put("fromSite", e.siteId());
        data.put("toSite", s.transferToSiteId());
        data.put("shippedAt", s.shipDateTimeUtc());
        data.put("carrier", s.carrierScac());
        data.put("trackingNo", s.trackingNo());
        data.put("wmsTxnId", s.wmsTxnId());
        data.put("lines", s.lines());
        publish(e, "transfer.shipped", data);
    }

    @KafkaListener(topics = IntegrationContracts.TOPIC_RECEIPT_CONFIRMATIONS, groupId = "webhooks.receipt-confirmations")
    public void onReceipt(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        ReceiptConfirmation r = codec.payload(e, ReceiptConfirmation.class);
        if (r.transferFromSiteId() == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transferNo", r.erpDocNo());
        data.put("fromSite", r.transferFromSiteId());
        data.put("toSite", e.siteId());
        data.put("receivedAt", r.receiptCompletedUtc());
        data.put("wmsTxnId", r.wmsTxnId());
        data.put("lines", r.lines());
        publish(e, "transfer.received", data);
    }

    @KafkaListener(topics = IntegrationContracts.TOPIC_GOODS_MOVEMENTS, groupId = "webhooks.goods-movements")
    public void onMovement(ConsumerRecord<String, String> record) {
        EventEnvelope e = codec.read(record.value());
        GoodsMovement m = codec.payload(e, GoodsMovement.class);
        String event = m.movementType() != null && m.movementType().startsWith("ISSUE_") ? "issue.posted"
                : m.movementType() != null && m.movementType().startsWith("RETURN_") && m.account() != null ? "issue.returned"
                : m.reasonCode() != null && COUNT_REASONS.contains(m.reasonCode()) ? "count.variance" : null;
        if (event == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("site", e.siteId());
        data.put("movementType", m.movementType());
        data.put("reasonCode", m.reasonCode());
        data.put("account", m.account());
        data.put("postedAt", m.physicalDateTimeUtc());
        data.put("wmsTxnId", m.wmsTxnId());
        data.put("items", m.items());
        publish(e, event, data);
    }

    private void publish(EventEnvelope e, String event, Map<String, Object> data) {
        inbox.once(e, "system:webhooks", "INTEGRATION", () -> webhooks.publish(event, e.messageId().toString(), data));
    }
}
