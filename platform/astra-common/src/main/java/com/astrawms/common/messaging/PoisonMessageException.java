package com.astrawms.common.messaging;

/**
 * A message that can never be processed as it is (scope §D.6.1 "Permanent technical"): malformed payload, unknown
 * schema version, unmappable content. The Kafka error handler sends it to the dead-letter topic immediately
 * instead of retrying.
 */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message) {
        super(message);
    }

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
