package com.astrawms.sapadapter.sap;

import java.util.List;

/**
 * The only boundary to the SAP system. Implementations: {@code MockSapGateway} (simulated SAP backend, used in
 * dev/test) and a JCo/RFC gateway for real systems (not part of this release). Both must:
 * <ul>
 *   <li>check whether a document with the WMS transaction ID already exists before posting (INT-014) and return it
 *       with {@code duplicate = true} instead of posting again;</li>
 *   <li>throw {@link SapTransientException} for retryable conditions (locks, communication failures);</li>
 *   <li>return business errors as {@link Bapi.Return} messages, never as exceptions.</li>
 * </ul>
 */
public interface SapGateway {

    record Result(boolean success, String materialDocument, String year, boolean duplicate, List<Bapi.Return> messages) {

        public Bapi.Return firstError() {
            return messages.stream().filter(Bapi.Return::isError).findFirst().orElse(null);
        }
    }

    Result confirmInboundDelivery(Bapi.InbDeliveryConfirmDec call);

    Result createGoodsMovement(Bapi.GoodsmvtCreate call);

    class SapTransientException extends RuntimeException {
        public SapTransientException(String message) {
            super(message);
        }
    }
}
