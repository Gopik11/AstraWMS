package com.astrawms.task.inbound;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Commands the task service sends to the inbound service for RF receiving (ADR-0019). The inbound service owns
 * receipts and returns; the task service only guides the operator and checks the scans. Problem codes are passed
 * through unchanged.
 */
public interface InboundClient {

    /** Receives one scan of a vendor delivery by item; returns the receipt progress. Idempotent by key. */
    JsonNode receiveAsnItem(String siteId, String docNo, String idempotencyKey, Map<String, Object> scan);

    /** Receives one graded return unit; returns the unit with its disposition. Idempotent by key. */
    JsonNode receiveReturnUnit(String siteId, String rmaNo, String idempotencyKey, Map<String, Object> unit);

    /** Closes a vendor receipt: posts the confirmation to the ERP. Lines received short need a reason. */
    JsonNode closeAsn(String siteId, String docNo, Map<String, String> shortReasons);

    /** Closes a return: posts receipt and disposition to the ERP. */
    JsonNode closeReturn(String siteId, String rmaNo);
}
