package com.agrocti.db;

import com.agrocti.model.Record;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generic JDBC repository for one table. It implements the parts that are the same for every table -
 * find by key, list with search and sort, delete, count - and leaves the table-specific SQL
 * (columns, mapping a row to an object, insert and update) to the subclasses (template-method pattern).
 *
 * @param <T> the record class stored in the table
 * @param <K> the type of its primary key
 */
public abstract class CrudRepository<T extends Record<K>, K> implements Dao<T, K> {

    /** Table name, e.g. "farm_nodes". */
    protected abstract String table();

    /** SELECT ... FROM ... (with joins) used for every read; the main table must have the alias "t". */
    protected abstract String selectSql();

    /** Turns the current row into an object. */
    protected abstract T map(ResultSet rs) throws SQLException;

    /** Columns the user may sort by: name used by the web page -> SQL column. A whitelist, so no SQL can be injected. */
    protected abstract Map<String, String> sortColumns();

    /** Columns searched by the search box. */
    protected abstract List<String> searchColumns();

    protected abstract T insert(T entity) throws SQLException;

    protected abstract void update(T entity) throws SQLException;

    protected abstract boolean isNew(T entity);

    @Override
    public T save(T entity) throws SQLException {
        if (isNew(entity)) return insert(entity);
        update(entity);
        return entity;
    }

    @Override
    public Optional<T> findById(K id) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(selectSql() + " WHERE t.id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public List<T> findAll() throws SQLException {
        return search(null, null, false);
    }

    /**
     * Lists the records whose searchable columns contain the text (case-insensitive), sorted by a whitelisted column.
     * @throws IllegalArgumentException if sortKey is not one of {@link #sortColumns()}
     */
    public List<T> search(String text, String sortKey, boolean descending) throws SQLException {
        String order = "t.id";
        if (sortKey != null && !sortKey.isEmpty()) {
            order = sortColumns().get(sortKey);
            if (order == null) throw new IllegalArgumentException("cannot sort by '" + sortKey + "'");
        }
        StringBuilder sql = new StringBuilder(selectSql());
        boolean filter = text != null && !text.isBlank();
        if (filter) sql.append(" WHERE CONCAT_WS(' ', ").append(String.join(", ", searchColumns())).append(") LIKE ?");
        sql.append(" ORDER BY ").append(order).append(descending ? " DESC" : " ASC").append(", t.id");
        List<T> out = new ArrayList<>();
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            if (filter) ps.setString(1, "%" + escapeLike(text.trim()) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    @Override
    public boolean delete(K id) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + table() + " WHERE id = ?")) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public int count() throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table());
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** % and _ typed by the user are searched literally. */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Reads an INT column that may be NULL. */
    protected static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    protected static void setNullableInt(PreparedStatement ps, int index, Integer v) throws SQLException {
        if (v == null) ps.setNull(index, java.sql.Types.INTEGER);
        else ps.setInt(index, v);
    }

    protected static String text(ResultSet rs, String column) throws SQLException {
        Object v = rs.getObject(column);
        return v == null ? "" : v.toString().replace('T', ' ').replaceAll("\\.0$", "");
    }
}
