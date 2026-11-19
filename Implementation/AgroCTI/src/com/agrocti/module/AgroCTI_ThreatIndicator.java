package com.agrocti.module;

import com.agrocti.config.Config;
import com.agrocti.db.CrudRepository;
import com.agrocti.db.Database;
import com.agrocti.exception.AgroCtiException;
import com.agrocti.exception.RecordNotFoundException;
import com.agrocti.exception.ValidationException;
import com.agrocti.model.ThreatIndicator;
import com.agrocti.model.UserAccount;
import com.agrocti.util.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Module 2 - Threat-intelligence indicators (N. Sri Varalaxmi, AP25110090062).
 * Table: threat_indicators. Holds the external feed (malicious IPs, regional advisories) and the
 * "local-" sightings shared by this network. Every change is pushed to the running detection pipeline.
 */
public class AgroCTI_ThreatIndicator extends CrudRepository<ThreatIndicator, String> implements CrudModule {

    private static final String UPSERT = "INSERT INTO threat_indicators"
            + " (id, type, value, region, valid_from, valid_to, confidence, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE type = VALUES(type), value = VALUES(value), region = VALUES(region),"
            + " valid_from = VALUES(valid_from), valid_to = VALUES(valid_to), confidence = VALUES(confidence), source = VALUES(source)";

    /** Called with the new list of external indicators after every change (observer). */
    private Consumer<List<ThreatIndicator>> feedListener = list -> { };

    public void setFeedListener(Consumer<List<ThreatIndicator>> listener) {
        this.feedListener = listener;
    }

    @Override protected String table() { return "threat_indicators"; }

    @Override protected String selectSql() { return "SELECT t.* FROM threat_indicators t"; }

    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("id", "t.id", "type", "t.type", "value", "t.value", "region", "t.region",
                "confidence", "t.confidence", "source", "t.source", "validFrom", "t.valid_from");
    }

    @Override protected List<String> searchColumns() { return List.of("t.id", "t.type", "t.value", "t.source"); }

    /** Indicators have text keys, so "new" means "not in the table yet". */
    @Override
    protected boolean isNew(ThreatIndicator t) {
        try {
            return findById(t.getId()).isEmpty();
        } catch (SQLException e) {
            return true;
        }
    }

    @Override
    protected ThreatIndicator map(ResultSet rs) throws SQLException {
        ThreatIndicator t = new ThreatIndicator(rs.getString("id"), rs.getString("type"), rs.getString("value"),
                rs.getInt("region"), rs.getInt("valid_from"), rs.getInt("valid_to"), rs.getInt("confidence"), rs.getString("source"));
        t.setCreatedAt(text(rs, "created_at"));
        return t;
    }

    private static void bind(PreparedStatement ps, ThreatIndicator t) throws SQLException {
        ps.setString(1, t.getId());
        ps.setString(2, t.getType());
        ps.setString(3, t.getValue());
        ps.setInt(4, t.getRegion());
        ps.setInt(5, t.getValidFrom());
        ps.setInt(6, t.getValidTo());
        ps.setInt(7, t.getConfidence());
        ps.setString(8, t.getSource());
    }

    @Override
    protected ThreatIndicator insert(ThreatIndicator t) throws SQLException {
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO threat_indicators (id, type, value, region, valid_from, valid_to, confidence, source)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            bind(ps, t);
            ps.executeUpdate();
        }
        return t;
    }

    @Override
    protected void update(ThreatIndicator t) throws SQLException {
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE threat_indicators SET type = ?, value = ?, region = ?, valid_from = ?, valid_to = ?,"
                        + " confidence = ?, source = ? WHERE id = ?")) {
            ps.setString(1, t.getType());
            ps.setString(2, t.getValue());
            ps.setInt(3, t.getRegion());
            ps.setInt(4, t.getValidFrom());
            ps.setInt(5, t.getValidTo());
            ps.setInt(6, t.getConfidence());
            ps.setString(7, t.getSource());
            ps.setString(8, t.getId());
            ps.executeUpdate();
        }
    }

    /** Insert-or-update of many indicators in one transaction: all are saved, or none. */
    public void saveAll(List<ThreatIndicator> list) throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                for (ThreatIndicator t : list) {
                    bind(ps, t);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Saves one sighting found by the regional correlator. */
    public void saveSighting(ThreatIndicator t) throws SQLException {
        saveAll(List.of(t));
    }

    /** Loads the feed file into the table the first time only, so later edits made in the browser are kept. */
    public boolean seedIfEmpty(List<ThreatIndicator> feed) throws SQLException {
        if (!external().isEmpty()) return false;
        saveAll(feed);
        return true;
    }

    /** All indicators except the local sightings - this is what the detection pipeline uses. */
    public List<ThreatIndicator> external() throws SQLException {
        return findAll().stream().filter(t -> !t.isLocal()).collect(Collectors.toList());
    }

    public List<ThreatIndicator> local() throws SQLException {
        return findAll().stream().filter(ThreatIndicator::isLocal).collect(Collectors.toList());
    }

    public void deleteLocal() throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM threat_indicators WHERE id LIKE 'local-%'")) {
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ CRUD with the course naming convention
    /** Create: gives the indicator the next free id if none was typed, validates it and checks for a duplicate IP. */
    public ThreatIndicator AP25110090062_indicator_create(ThreatIndicator t) throws SQLException, ValidationException {
        if (t.getId() == null || t.getId().isBlank()) t = withId(t, nextId());
        t.validate();
        if (t.isLocal()) throw new ValidationException("id", "ids starting with local- are reserved for sightings of this network");
        checkDuplicateIp(t);
        try {
            insert(t);
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new ValidationException("id", "an indicator with id " + t.getId() + " already exists");
        }
        feedListener.accept(external());
        return t;
    }

    /** Retrieve: search and sort all indicators. */
    public List<ThreatIndicator> AP25110090062_indicator_retrive(String search, String sort, boolean desc) throws SQLException {
        return search(search, sort, desc);
    }

    /** Update: local sightings are produced by the correlator and cannot be edited by hand. */
    public ThreatIndicator AP25110090062_indicator_update(ThreatIndicator t) throws SQLException, AgroCtiException {
        ThreatIndicator old = findById(t.getId()).orElseThrow(() -> new RecordNotFoundException("indicator", t.getId()));
        if (old.isLocal()) throw new ValidationException(null, "local sightings are written by the correlator and cannot be edited");
        t.validate();
        checkDuplicateIp(t);
        update(t);
        feedListener.accept(external());
        return t;
    }

    /** Delete: removes the indicator; the pipeline stops using it at once. */
    public void AP25110090062_indicator_delete(String id) throws SQLException, AgroCtiException {
        if (!delete(id)) throw new RecordNotFoundException("indicator", id);
        feedListener.accept(external());
    }

    private void checkDuplicateIp(ThreatIndicator t) throws SQLException, ValidationException {
        if (!t.isIp()) return;
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM threat_indicators WHERE type = 'ipv4' AND value = ? AND id <> ?")) {
            ps.setString(1, t.getValue());
            ps.setString(2, t.getId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) throw new ValidationException("value", t.getValue() + " is already listed as " + rs.getString(1));
            }
        }
    }

    private String nextId() throws SQLException {
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(CAST(SUBSTRING(id, 5) AS UNSIGNED)), 0) + 1 FROM threat_indicators WHERE id LIKE 'ind-%'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return String.format("ind-%03d", rs.getInt(1));
        }
    }

    private static ThreatIndicator withId(ThreatIndicator t, String id) {
        return new ThreatIndicator(id, t.getType(), t.getValue(), t.getRegion(), t.getValidFrom(), t.getValidTo(),
                t.getConfidence(), t.getSource());
    }

    // ------------------------------------------------------------------ CrudModule (web API)
    @Override public String key() { return "indicators"; }

    @Override
    public String list(String search, String sort, boolean desc) throws SQLException {
        return Json.array(AP25110090062_indicator_retrive(search, sort, desc));
    }

    private static ThreatIndicator fromForm(String id, Form f) throws ValidationException {
        String type = f.text("type");
        int confidence = f.integer("confidence");
        if ("ipv4".equals(type)) return new ThreatIndicator(id, "ipv4", f.text("value"), -1, 0, Integer.MAX_VALUE, confidence, f.text("source"));
        int region = Config.regionIndex(f.text("region"));
        if (region < 0) throw new ValidationException("region", "choose a region");
        return new ThreatIndicator(id, type, f.text("value"), region, f.step("validFromTime"), f.step("validToTime"), confidence, f.text("source"));
    }

    @Override
    public String create(Form f, UserAccount by) throws SQLException, AgroCtiException {
        return AP25110090062_indicator_create(fromForm(f.text("id").toLowerCase(), f)).toJson();
    }

    @Override
    public String update(String id, Form f, UserAccount by) throws SQLException, AgroCtiException {
        return AP25110090062_indicator_update(fromForm(id, f)).toJson();
    }

    @Override
    public String remove(String id, UserAccount by) throws SQLException, AgroCtiException {
        AP25110090062_indicator_delete(id);
        return "indicator " + id + " deleted";
    }

    @Override
    public String options() throws SQLException {
        return CrudModule.optionsJson(external());
    }
}
