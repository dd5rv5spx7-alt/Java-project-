package com.agrocti.exception;

/** Update or delete of a record whose key does not exist. */
public class RecordNotFoundException extends AgroCtiException {
    public RecordNotFoundException(String what, Object id) {
        super(what + " " + id + " does not exist");
    }
}
