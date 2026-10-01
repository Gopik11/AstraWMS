package com.astrawms.sapadapter.mapping;

/** A message that cannot be mapped (ISD class "Permanent technical"): rejected with IDoc status 51, never retried. */
public class MappingException extends RuntimeException {

    private final String code;

    public MappingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
