package com.astrawms.sapadapter.mapping;

import com.astrawms.common.messaging.PoisonMessageException;

/** A message that cannot be mapped (ISD class "Permanent technical"): rejected with IDoc status 51, never retried. */
public class MappingException extends PoisonMessageException {

    private final String code;

    public MappingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
