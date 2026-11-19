package com.agrocti.db;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Generic data-access interface with two type parameters: the entity T and its key K. */
public interface Dao<T, K> {
    T save(T entity) throws SQLException;
    Optional<T> findById(K id) throws SQLException;
    List<T> findAll() throws SQLException;
    boolean delete(K id) throws SQLException;
}
