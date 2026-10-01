package com.astrawms.inbound.integration;

import com.astrawms.common.contracts.IntegrationContracts;
import com.astrawms.common.contracts.IntegrationContracts.ErpPostingResult;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptConfirmation;
import com.astrawms.common.contracts.IntegrationContracts.ReceiptExpectation;
import com.astrawms.common.messaging.EnvelopeCodec;
import com.astrawms.common.messaging.EventEnvelope;
import com.astrawms.common.messaging.InboxGuard;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.inbound.expectation.ExpectationService;
import com.astrawms.inbound.receiving.ReceivingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Consumes ERP-originated messages from the adapters: expectations (IF-IB-001) and posting results (IF-IB-002). */
@Component
public class IntegrationListeners {

    private final EnvelopeCodec codec;
    private final InboxGuard inbox;
    private final ExpectationService expectations;
    private final ReceivingService receiving;
    private final TransactionTemplate tx;

    public IntegrationListeners(EnvelopeCodec codec, InboxGuard inbox, ExpectationService expectations,
                                ReceivingService receiving, TransactionTemplate tx) {
        this.codec = codec;
        this.inbox = inbox;
        this.expectations = expectations;
        this.receiving = receiving;
        this.tx = tx;
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
        if (!ReceiptConfirmation.TYPE.equals(result.sourceMessageType())) {
            return; // results for other flows (e.g. goods movements) belong to other services
        }
        process(envelope, () -> receiving.onPostingResult(result));
    }

    private void process(EventEnvelope envelope, Runnable work) {
        TenantContext.runAs(new TenantContext.Scope(envelope.tenantId(), "system:" + envelope.sourceSystem(), "ERP"),
                () -> tx.executeWithoutResult(status -> {
                    if (inbox.firstDelivery(envelope.sourceSystem(), envelope.messageId().toString(), envelope.messageType())) {
                        work.run();
                    }
                }));
    }
}
