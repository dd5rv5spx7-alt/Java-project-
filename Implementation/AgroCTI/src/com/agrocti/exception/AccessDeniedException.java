package com.agrocti.exception;

/** The logged-in user's role is not allowed to do this. */
public class AccessDeniedException extends AgroCtiException {
    public AccessDeniedException(String message) {
        super(message);
    }
}
