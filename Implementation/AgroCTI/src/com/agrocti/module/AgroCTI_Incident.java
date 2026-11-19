package com.agrocti.module;

import com.agrocti.config.Config;
import com.agrocti.db.CrudRepository;
import com.agrocti.db.Database;
import com.agrocti.exception.AgroCtiException;
import com.agrocti.exception.RecordNotFoundException;
import com.agrocti.exception.ValidationException;
import com.agrocti.model.Alert;
import com.agrocti.model.IncidentStatus;
import com.agrocti.model.Severity;
import com.agrocti.model.UserAccount;
import com.agrocti.util.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Module 3 - Incident management (M. Mohana Manogna, AP25110090170).
 * Table: incidents. The live detector inserts incidents automatically; analysts can also report one by hand,
 * then set its status, add a note, assign it and delete it.
 */
public class AgroCTI_Incident extends CrudRepository<Alert, Integer> implements CrudModule {

    private final AgroCTI_User users = new AgroCTI_User();
    private IntSupplier clock = () -> Config.VAL_END;     // current step of the live replay

    public void setClock(IntSupplier clock) {
        this.clock = clock;
    }

    @Override protected String table() { return "incidents"; }

    @Override
    protected String selectSql() {
        return "SELECT t.*, u.full_name AS assigned_name, (SELECT COUNT(*) FROM response_actions a WHERE a.incident_id = t.id)"
                + " AS action_count FROM incidents t LEFT JOIN users u ON u.id = t.assigned_to";
    }

    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("id", "t.id", "nodeName", "t.node_name", "region", "t.region", "time", "t.start_step",
                "severity", "FIELD(t.severity, 'ALERT', 'CRITICAL')", "score", "t.max_score", "status", "t.status",
                "guess", "t.guess", "assignedName", "assigned_name", "durationMin", "t.end_step - t.start_step");
    }

    @Override
    protected List<String> searchColumns() {
        return List.of("t.node_name", "t.region", "t.severity", "t.guess", "t.status", "t.reasons", "t.analyst_note", "u.full_name");
    }

    @Override protected boolean isNew(Alert a) { return a.getId() == 0; }

    @Override
    protected Alert map(ResultSet rs) throws SQLException {
        Alert a = new Alert(rs.getInt("id"), rs.getInt("node_id"), rs.getInt("start_step"), rs.getInt("end_step"),
                rs.getInt("readings"), Severity.valueOf(rs.getString("severity")), rs.getDouble("max_score"),
                rs.getString("guess"), rs.getString("reasons"), rs.getString("truth"));
        a.setStatus(IncidentStatus.valueOf(rs.getString("status")));
        a.setNote(rs.getString("analyst_note"));
        a.setAssignedTo(nullableInt(rs, "assigned_to"));
        a.setAssignedName(rs.getString("assigned_name"));
        a.setCreatedAt(text(rs, "created_at"));
        return a;
    }

    /** Insert - used both by the live detector (through the writer thread) and by the create form. */
    @Override
    protected Alert insert(Alert a) throws SQLException {
        String sql = "INSERT INTO incidents (node_id, node_name, region, start_step, end_step, readings, severity,"
                + " max_score, guess, reasons, truth, status, analyst_note, assigned_to) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, a.getNode());
            ps.setString(2, Config.nodeName(a.getNode()));
            ps.setString(3, Config.REGIONS[Config.regionOf(a.getNode())]);
            ps.setInt(4, a.getStartStep());
            ps.setInt(5, a.getEndStep());
            ps.setInt(6, a.getReadings());
            ps.setString(7, a.getSeverity().name());
            ps.setDouble(8, a.getMaxScore());
            ps.setString(9, a.getGuess());
            ps.setString(10, a.getReasons());
            ps.setString(11, a.getTruth());
            ps.setString(12, a.getStatus().name());
            ps.setString(13, a.getNote());
            setNullableInt(ps, 14, a.getAssignedTo());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) a.setId(keys.getInt(1));
            }
        }
        return a;
    }

    /**
     * Update by the live detector: only the detection columns change, so a status or note
     * typed by an analyst in the meantime is kept.
     */
    @Override
    protected void update(Alert a) throws SQLException {
        String sql = "UPDATE incidents SET end_step = ?, readings = ?, severity = ?, max_score = ?, reasons = ?, truth = ? WHERE id = ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, a.getEndStep());
            ps.setInt(2, a.getReadings());
            ps.setString(3, a.getSeverity().name());
            ps.setDouble(4, a.getMaxScore());
            ps.setString(5, a.getReasons());
            ps.setString(6, a.getTruth());
            ps.setInt(7, a.getId());
            ps.executeUpdate();
        }
    }

    /** Update by an analyst: the work-flow columns. */
    private void updateReview(Alert a) throws SQLException {
        String sql = "UPDATE incidents SET status = ?, analyst_note = ?, assigned_to = ?, severity = ?, guess = ? WHERE id = ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a.getStatus().name());
            ps.setString(2, a.getNote());
            setNullableInt(ps, 3, a.getAssignedTo());
            ps.setString(4, a.getSeverity().name());
            ps.setString(5, a.getGuess());
            ps.setInt(6, a.getId());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ CRUD with the course naming convention
    /** Create: an incident reported by hand by an analyst (for example a farmer phoned in). */
    public Alert AP25110090170_incident_create(Alert a, UserAccount by) throws SQLException, ValidationException {
        a.validate();
        users.requireStaff(a.getAssignedTo(), "assignedTo");
        a.setReasons("Reported by hand by " + (by == null ? "an analyst" : by.getFullName()));
        a.setTruth("manual report");
        return insert(a);
    }

    /** Retrieve: search and sort; the newest come first by default. */
    public List<Alert> AP25110090170_incident_retrive(String search, String sort, boolean desc) throws SQLException {
        return search(search, sort == null || sort.isEmpty() ? "id" : sort, sort == null || sort.isEmpty() || desc);
    }

    /** Update: status, note, assignee, severity and attack type. Closing needs a note. */
    public Alert AP25110090170_incident_update(int id, IncidentStatus status, String note, Integer assignedTo,
                                               Severity severity, String guess) throws SQLException, AgroCtiException {
        Alert a = findById(id).orElseThrow(() -> new RecordNotFoundException("incident", id));
        a.setStatus(status);
        a.setNote(note);
        a.setAssignedTo(assignedTo);
        a.setSeverity(severity);
        a.setGuess(guess);
        a.validate();
        users.requireStaff(assignedTo, "assignedTo");
        updateReview(a);
        return findById(id).orElseThrow();
    }

    /** Delete: also deletes the incident's response actions (ON DELETE CASCADE); returns how many. */
    public int AP25110090170_incident_delete(int id) throws SQLException, AgroCtiException {
        int actions;
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM response_actions WHERE incident_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                actions = rs.getInt(1);
            }
        }
        if (!delete(id)) throw new RecordNotFoundException("incident", id);
        return actions;
    }

    // ------------------------------------------------------------------ used by the live detector and dashboard
    public List<Alert> latest(int limit) throws SQLException {
        List<Alert> out = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(selectSql() + " ORDER BY t.end_step DESC, t.id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    public Map<Severity, Integer> countBySeverity() throws SQLException {
        Map<Severity, Integer> m = new EnumMap<>(Severity.class);
        try (Connection c = Database.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT severity, COUNT(*) FROM incidents GROUP BY severity")) {
            while (rs.next()) m.put(Severity.valueOf(rs.getString(1)), rs.getInt(2));
        }
        return m;
    }

    /** Clears the table at the start of a new live replay (response actions go with them). */
    public int deleteAll() throws SQLException {
        try (Connection c = Database.getConnection(); Statement s = c.createStatement()) {
            return s.executeUpdate("DELETE FROM incidents");
        }
    }

    // ------------------------------------------------------------------ CrudModule (web API)
    @Override public String key() { return "incidents"; }

    @Override
    public String list(String search, String sort, boolean desc) throws SQLException {
        return Json.array(AP25110090170_incident_retrive(search, sort, desc));
    }

    @Override
    public String create(Form f, UserAccount by) throws SQLException, AgroCtiException {
        int step = clock.getAsInt();
        Alert a = new Alert(f.integer("node"), step, f.choice("severity", Severity.class), 0, f.text("guess"), "");
        a.setStatus(f.has("status") ? f.choice("status", IncidentStatus.class) : IncidentStatus.OPEN);
        a.setNote(f.text("note"));
        a.setAssignedTo(f.optionalInteger("assignedTo"));
        return AP25110090170_incident_create(a, by).toJson();
    }

    @Override
    public String update(String id, Form f, UserAccount by) throws SQLException, AgroCtiException {
        return AP25110090170_incident_update(CrudModule.intId(id), f.choice("status", IncidentStatus.class), f.text("note"),
                f.optionalInteger("assignedTo"), f.choice("severity", Severity.class), f.text("guess")).toJson();
    }

    @Override
    public String remove(String id, UserAccount by) throws SQLException, AgroCtiException {
        int n = CrudModule.intId(id);
        int actions = AP25110090170_incident_delete(n);
        return "incident " + n + " deleted" + (actions > 0 ? " with its " + actions + " response action(s)" : "");
    }

    /** Open incidents only: closed ones get no new response actions. */
    @Override
    public String options() throws SQLException {
        List<Alert> open = new ArrayList<>();
        for (Alert a : AP25110090170_incident_retrive(null, null, true)) if (!a.getStatus().isClosed()) open.add(a);
        return CrudModule.optionsJson(open);
    }
}
