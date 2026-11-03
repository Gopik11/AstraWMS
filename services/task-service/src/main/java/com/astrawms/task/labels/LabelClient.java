package com.astrawms.task.labels;

import java.util.Optional;

/**
 * The state of a printed label (ADR-0025), from master data: PRINTED (printed, not yet verified, so not active),
 * VERIFIED or VOID. Empty when the barcode was never printed by AstraWMS (a vendor SSCC, a hand-written bin).
 * {@link #UNREACHABLE} when master data could not be asked: the task service then applies the site's label-check
 * policy (ADR-0029).
 */
public interface LabelClient {

    String UNREACHABLE = "UNREACHABLE";

    Optional<String> status(String siteId, String type, String barcode);
}
