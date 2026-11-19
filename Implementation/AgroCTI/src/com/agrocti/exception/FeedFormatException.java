package com.agrocti.exception;

/** The threat-intelligence feed file could not be read or has a wrong entry. */
public class FeedFormatException extends AgroCtiException {
    public FeedFormatException(String message) {
        super(message);
    }
}
