package com.agrocti.model;

import com.agrocti.exception.ValidationException;
import com.agrocti.util.Json;

/**
 * Abstract parent of every record that the user can create, read, update and delete in the browser
 * (farm node, threat indicator, incident, response action, user account).
 * It fixes what all records share - a key, a creation time, validation and a short label -
 * and leaves the details to each subclass.
 */
public abstract class Record<K> implements Identifiable<K>, Json.Writable {

    private String createdAt = "";

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt == null ? "" : createdAt; }

    /** Checks every field against the business rules; throws on the first problem. */
    public abstract void validate() throws ValidationException;

    /** Short human-readable name, used in drop-down lists and messages. */
    public abstract String label();

    // ------------------------------------------------------------------ helpers for subclasses
    protected static String requireText(String field, String value, int maxLength) throws ValidationException {
        if (value == null || value.isBlank()) throw new ValidationException(field, field + " is required");
        String v = value.trim();
        if (v.length() > maxLength) throw new ValidationException(field, field + " must be at most " + maxLength + " characters");
        return v;
    }

    protected static void requireMatch(String field, String value, String regex, String example) throws ValidationException {
        if (value == null || !value.matches(regex)) throw new ValidationException(field, field + " must look like " + example);
    }

    protected static void requireRange(String field, int value, int min, int max) throws ValidationException {
        if (value < min || value > max) throw new ValidationException(field, field + " must be between " + min + " and " + max);
    }

    /** True for a dotted IPv4 address whose four parts are 0..255. */
    public static boolean isIpv4(String s) {
        if (s == null || !s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        for (String part : s.split("\\.")) if (Integer.parseInt(part) > 255) return false;
        return true;
    }
}
