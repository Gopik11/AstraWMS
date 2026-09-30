package com.astrawms.common.messaging;

import tools.jackson.databind.json.JsonMapper;

/** Reads canonical envelopes from Kafka record values and converts payloads to typed records. */
public class EnvelopeCodec {

    private final JsonMapper json;

    public EnvelopeCodec(JsonMapper json) {
        this.json = json;
    }

    public EventEnvelope read(String value) {
        return json.readValue(value, EventEnvelope.class);
    }

    public <T> T payload(EventEnvelope envelope, Class<T> type) {
        return json.treeToValue(envelope.payload(), type);
    }
}
