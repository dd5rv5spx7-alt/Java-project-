package com.agrocti.exception;

/** Base class of all checked exceptions thrown by the framework. */
public class AgroCtiException extends Exception {
    public AgroCtiException(String message) {
        super(message);
    }

    public AgroCtiException(String message, Throwable cause) {
        super(message, cause);
    }
}
