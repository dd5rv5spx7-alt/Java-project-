package com.agrocti.module;

import com.agrocti.exception.AgroCtiException;
import com.agrocti.model.Role;
import com.agrocti.model.UserAccount;

import java.sql.SQLException;

/**
 * What the web server needs from every record module. The server keeps the five modules in a
 * Map&lt;String, CrudModule&gt; and calls them through this interface without knowing which one it has
 * (run-time polymorphism).
 */
public interface CrudModule {

    /** Short name used in URLs: /api/records/{key}. */
    String key();

    /** JSON array of records matching the search text, sorted by the given column. */
    String list(String search, String sort, boolean descending) throws SQLException;

    String create(Form form, UserAccount by) throws SQLException, AgroCtiException;

    String update(String id, Form form, UserAccount by) throws SQLException, AgroCtiException;

    /** Returns a message describing what was deleted. */
    String remove(String id, UserAccount by) throws SQLException, AgroCtiException;

    /** JSON array of {id, label} pairs for drop-down lists in other forms. */
    String options() throws SQLException;

    /** Who may list the records. Every logged-in user, unless a module says otherwise. */
    default boolean canRead(Role role) {
        return true;
    }

    /** Who may create, update and delete. */
    default boolean canWrite(Role role) {
        return role.canEditRecords();
    }

    /** JSON [{id, label}, ...] for any list of records (bounded wildcard: any Record subclass). */
    static String optionsJson(java.util.List<? extends com.agrocti.model.Record<?>> records) {
        StringBuilder sb = new StringBuilder("[");
        for (com.agrocti.model.Record<?> r : records) {
            if (sb.length() > 1) sb.append(',');
            sb.append(com.agrocti.util.Json.object().put("id", String.valueOf(r.getId())).put("label", r.label()).build());
        }
        return sb.append(']').toString();
    }

    /** Helper shared by the modules: parses a numeric key from the URL. */
    static int intId(String id) throws AgroCtiException {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            throw new AgroCtiException("'" + id + "' is not a valid record number");
        }
    }
}
