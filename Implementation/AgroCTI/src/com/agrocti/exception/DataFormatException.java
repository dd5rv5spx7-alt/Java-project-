package com.agrocti.exception;

/** A line of a telemetry or actuator CSV file is malformed. */
public class DataFormatException extends AgroCtiException {
    public DataFormatException(String file, int line, String message) {
        super(file + " line " + line + ": " + message);
    }
}
