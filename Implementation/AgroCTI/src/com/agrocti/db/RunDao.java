package com.agrocti.db;

import com.agrocti.eval.ModelResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Keeps a history of every evaluation run so results can be compared over time. */
public class RunDao {

    public void saveAll(long seed, List<ModelResult> results) throws SQLException {
        String sql = "INSERT INTO model_runs (run_at, seed, model, kind, f1, precision_, recall, fpr, parameters,"
                + " memory_bytes, latency_us) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        java.sql.Timestamp now = java.sql.Timestamp.valueOf(LocalDateTime.now().withNano(0));
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (ModelResult r : results) {
                ps.setTimestamp(1, now);
                ps.setLong(2, seed);
                ps.setString(3, r.name);
                ps.setString(4, r.kind);
                ps.setDouble(5, r.metrics.f1());
                ps.setDouble(6, r.metrics.precision());
                ps.setDouble(7, r.metrics.recall());
                ps.setDouble(8, r.metrics.fpr());
                ps.setLong(9, r.parameters);
                ps.setLong(10, r.memoryBytes);
                ps.setDouble(11, r.latencyMicros);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public int count() throws SQLException {
        try (Connection c = Database.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM model_runs")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public List<String> lastRunSummary() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = Database.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT model, f1, fpr FROM model_runs WHERE run_at = (SELECT MAX(run_at) FROM model_runs)")) {
            while (rs.next()) out.add(String.format("%s: F1 %.1f%%, FPR %.2f%%", rs.getString(1), 100 * rs.getDouble(2), 100 * rs.getDouble(3)));
        }
        return out;
    }
}
