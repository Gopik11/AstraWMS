package com.astrawms.inventory.outbound;

import java.math.BigDecimal;
import java.util.List;

/**
 * What open orders and transfers hold and wait for (ADR-0025), from the outbound service: per source site and item,
 * allocated to orders and to transfers and still short on each; per receiving store, what open transfers will bring.
 */
public interface OutboundClient {

    record SourceCommitment(String siteId, String ownerId, String itemNo, BigDecimal allocatedOrders,
                            BigDecimal allocatedTransfers, BigDecimal shortOrders, BigDecimal shortTransfers) {
    }

    record OpenTransfer(String siteId, String ownerId, String itemNo, String fromSite, BigDecimal openQty, String transfers) {
    }

    record Commitments(List<SourceCommitment> bySource, List<OpenTransfer> toStore, boolean available) {
        public static final Commitments UNAVAILABLE = new Commitments(List.of(), List.of(), false);
    }

    Commitments commitments();
}
