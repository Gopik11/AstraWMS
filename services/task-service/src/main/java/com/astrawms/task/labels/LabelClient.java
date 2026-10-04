package com.astrawms.task.labels;

import java.util.Optional;

/**
 * The state of a printed label (ADR-0025), from master data: PRINTED (printed, not yet verified, so not active),
 * VERIFIED or VOID. Empty when the barcode was never printed by AstraWMS (a vendor SSCC, a hand-written bin).
 */
public interface LabelClient {

    Optional<String> status(String siteId, String type, String barcode);
}
