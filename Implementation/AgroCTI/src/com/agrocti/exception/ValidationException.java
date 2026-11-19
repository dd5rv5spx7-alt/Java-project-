package com.agrocti.exception;

/** A record that breaks a business rule or has a missing / badly formatted field. */
public class ValidationException extends AgroCtiException {
    private final String field;

    public ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** Name of the form field that is wrong (null when the rule concerns the whole record). */
    public String getField() {
        return field;
    }
}
