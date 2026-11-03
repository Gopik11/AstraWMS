package com.astrawms.inbound.integration;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ErpPostingResult;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptConfirmation;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptExpectation;
import com.astrawms.common.messaging.EnvelopeCodec;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.InboxProcessing;
import com.astrawms.inbound.expectation.ExpectationService;
import com.astrawms.inbound.receiving.ReceivingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes ERP-originated messages from the adapters: expectations (IF-IB-001) and posting results (IF-IB-002). */
@Component
public class IntegrationListeners {

    private final EnvelopeCodec codec;
    private final InboxProcessing inbox;
    private final ExpectationService expectations;
    private final ReceivingService receiving;
    private final com.astrawms.inbound.returns.ReturnsService returns;

    public IntegrationListeners(EnvelopeCodec codec, InboxProcessing inbox, ExpectationService expectations,
                                ReceivingService receiving, com.astrawms.inbound.returns.ReturnsService returns) {
        this.codec = codec;
        this.inbox = inbox;
        this.expectations = expectations;
        this.receiving = receiving;
        this.returns = returns;
    }

    @KafkaListener(topics = IntegrationContracts.TOPIC_RECEIPT_EXPECTATIONS, groupId = "inbound-service.expectations")
    public void onExpectation(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = codec.read(record.value());
        process(envelope, () -> expectations.apply(envelope, codec.payload(envelope, ReceiptExpectation.class)));
    }

    @KafkaListener(topics = IntegrationContracts.TOPIC_ERP_POSTING_RESULTS, groupId = "inbound-service.posting-results")
    public void onPostingResult(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = codec.read(record.value());
        ErpPostingResult result = codec.payload(envelope, ErpPostingResult.class);
        if (com.astrawms.common.contracts.ReturnsContracts.ReturnConfirmation.TYPE.equals(result.sourceMessageType())) {
            process(envelope, () -> returns.onPostingResult(result));
            return;
        }
        if (!ReceiptConfirmation.TYPE.equals(result.sourceMessageType())) {
            return; // results for other flows (e.g. goods movements) belong to other services
        }
        process(envelope, () -> receiving.onPostingResult(result));
    }

    @KafkaListener(topics = com.astrawms.common.contracts.ReturnsContracts.TOPIC_RETURN_EXPECTATIONS,
                   groupId = "inbound-service.returns")
    public void onReturnExpectation(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = codec.read(record.value());
        process(envelope, () -> returns.apply(envelope,
                codec.payload(envelope, com.astrawms.common.contracts.ReturnsContracts.ReturnExpectation.class)));
    }

    private void process(EventEnvelope envelope, Runnable work) {
        inbox.once(envelope, "system:" + envelope.sourceSystem(), "ERP", work);
    }
}
