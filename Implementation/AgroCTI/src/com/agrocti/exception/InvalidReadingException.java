package com.agrocti.exception;

/** A sensor value that is physically impossible (for example soil moisture above 100 %). */
public class InvalidReadingException extends AgroCtiException {
    private final int feature;

    public InvalidReadingException(int feature, String message) {
        super(message);
        this.feature = feature;
    }

    public int getFeature() {
        return feature;
    }
}
