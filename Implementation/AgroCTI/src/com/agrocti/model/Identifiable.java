package com.agrocti.model;

/** Anything that is stored in the database with a key of type K. */
public interface Identifiable<K> {
    K getId();
}
