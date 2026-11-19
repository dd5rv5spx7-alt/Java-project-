package com.agrocti.module;

import com.agrocti.config.Config;
import com.agrocti.exception.ValidationException;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Typed access to the fields of a form sent by the browser as JSON. */
public final class Form {

    private final Map<String, Object> values;

    public Form(Map<String, Object> values) {
        this.values = values;
    }

    public boolean has(String key) {
        Object v = values.get(key);
        return v != null && !v.toString().isBlank();
    }

    /** Text field (trimmed); empty string if missing. */
    public String text(String key) {
        Object v = values.get(key);
        return v == null ? "" : v.toString().trim();
    }

    /** Whole number; the field is required. */
    public int integer(String key) throws ValidationException {
        if (!has(key)) throw new ValidationException(key, key + " is required");
        return parse(key);
    }

    /** Whole number or null when the field is empty. */
    public Integer optionalInteger(String key) throws ValidationException {
        return has(key) ? parse(key) : null;
    }

    private int parse(String key) throws ValidationException {
        Object v = values.get(key);
        try {
            double d = v instanceof Double ? (Double) v : Double.parseDouble(v.toString().trim());
            if (d != Math.rint(d)) throw new NumberFormatException();
            return (int) d;
        } catch (NumberFormatException e) {
            throw new ValidationException(key, key + " must be a whole number");
        }
    }

    /** Generic method: reads any enum constant by name, e.g. choice("status", NodeStatus.class). */
    public <E extends Enum<E>> E choice(String key, Class<E> type) throws ValidationException {
        if (!has(key)) throw new ValidationException(key, key + " is required");
        try {
            return Enum.valueOf(type, text(key).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(key, "'" + text(key) + "' is not a valid " + key);
        }
    }

    /** A date-time from an HTML datetime-local input, as a simulation step. */
    public int step(String key) throws ValidationException {
        if (!has(key)) throw new ValidationException(key, key + " is required");
        try {
            return Config.stepOf(LocalDateTime.parse(text(key)));
        } catch (DateTimeParseException e) {
            throw new ValidationException(key, key + " must be a date and time");
        }
    }
}
