package com.agrocti.module;

import com.agrocti.db.CrudRepository;
import com.agrocti.db.Database;
import com.agrocti.exception.AgroCtiException;
import com.agrocti.exception.RecordNotFoundException;
import com.agrocti.exception.ValidationException;
import com.agrocti.model.ActionStatus;
import com.agrocti.model.ActionType;
import com.agrocti.model.IncidentStatus;
import com.agrocti.model.ResponseAction;
import com.agrocti.model.UserAccount;
import com.agrocti.util.Json;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Module 4 - Response actions (M. Mukesh Vasudhar, AP25110090048).
 * Table: response_actions, a child of incidents. Adding the first action to an OPEN incident moves the
 * incident to INVESTIGATING in the same transaction.
 */
public class AgroCTI_ResponseAction extends CrudRepository<ResponseAction, Integer> implements CrudModule {

    private final AgroCTI_User users = new AgroCTI_User();

    @Override protected String table() { return "response_actions"; }

    @Override
    protected String selectSql() {
        return "SELECT t.*, u.full_name AS performer, CONCAT('#', i.id, ' ', i.node_name, ' - ', i.guess) AS incident_label"
                + " FROM response_actions t JOIN incidents i ON i.id = t.incident_id LEFT JOIN users u ON u.id = t.performed_by";
    }

    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("id", "t.id", "incidentLabel", "t.incident_id", "type", "t.action_type", "status", "t.status",
                "dueDate", "t.due_date", "performedByName", "performer", "createdAt", "t.created_at");
    }

    @Override
    protected List<String> searchColumns() {
        return List.of("t.action_type", "t.description", "t.status", "u.full_name", "i.node_name", "CAST(t.incident_id AS CHAR)");
    }

    @Override protected boolean isNew(ResponseAction a) { return a.getId() == 0; }

    @Override
    protected ResponseAction map(ResultSet rs) throws SQLException {
        Date due = rs.getDate("due_date");
        ResponseAction a = new ResponseAction(rs.getInt("incident_id"), ActionType.valueOf(rs.getString("action_type")),
                rs.getString("description"), nullableInt(rs, "performed_by"), ActionStatus.valueOf(rs.getString("status")),
                due == null ? "" : due.toString());
        a.setId(rs.getInt("id"));
        a.setPerformedByName(rs.getString("performer"));
        a.setIncidentLabel(rs.getString("incident_label"));
        a.setCreatedAt(text(rs, "created_at"));
        return a;
    }

    private static void bind(PreparedStatement ps, ResponseAction a) throws SQLException {
        ps.setInt(1, a.getIncidentId());
        ps.setString(2, a.getType().name());
        ps.setString(3, a.getDescription());
        setNullableInt(ps, 4, a.getPerformedBy());
        ps.setString(5, a.getStatus().name());
        if (a.getDueDate().isEmpty()) ps.setNull(6, java.sql.Types.DATE);
        else ps.setDate(6, Date.valueOf(a.getDueDate()));
    }

    @Override
    protected ResponseAction insert(ResponseAction a) throws SQLException {
        try (Connection c = Database.getConnection()) {
            return insert(c, a);
        }
    }

    private ResponseAction insert(Connection c, ResponseAction a) throws SQLException {
        String sql = "INSERT INTO response_actions (incident_id, action_type, description, performed_by, status, due_date)"
                + " VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, a);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                if (k.next()) a.setId(k.getInt(1));
            }
        }
        return a;
    }

    @Override
    protected void update(ResponseAction a) throws SQLException {
        String sql = "UPDATE response_actions SET incident_id = ?, action_type = ?, description = ?, performed_by = ?,"
                + " status = ?, due_date = ? WHERE id = ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, a);
            ps.setInt(7, a.getId());
            ps.executeUpdate();
        }
    }

    private static IncidentStatus incidentStatus(Connection c, int incidentId) throws SQLException, ValidationException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM incidents WHERE id = ? FOR UPDATE")) {
            ps.setInt(1, incidentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ValidationException("incidentId", "incident " + incidentId + " does not exist");
                return IncidentStatus.valueOf(rs.getString(1));
            }
        }
    }

    // ------------------------------------------------------------------ CRUD with the course naming convention
    /**
     * Create: inserts the action and, if the incident was OPEN, sets it to INVESTIGATING.
     * Both statements run in one transaction - if either fails, neither is kept (rollback).
     */
    public ResponseAction AP25110090048_action_create(ResponseAction a) throws SQLException, ValidationException {
        a.validate();
        users.requireStaff(a.getPerformedBy(), "performedBy");
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                IncidentStatus s = incidentStatus(c, a.getIncidentId());
                if (s.isClosed()) {
                    throw new ValidationException("incidentId", "incident " + a.getIncidentId() + " is " + s + "; reopen it before adding actions");
                }
                insert(c, a);
                if (s == IncidentStatus.OPEN) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE incidents SET status = 'INVESTIGATING' WHERE id = ?")) {
                        ps.setInt(1, a.getIncidentId());
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | ValidationException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        return findById(a.getId()).orElse(a);
    }

    /** Retrieve: search and sort all actions. */
    public List<ResponseAction> AP25110090048_action_retrive(String search, String sort, boolean desc) throws SQLException {
        return search(search, sort, desc);
    }

    /** Update: type, description, performer, status and due date. */
    public ResponseAction AP25110090048_action_update(ResponseAction a) throws SQLException, AgroCtiException {
        ResponseAction old = findById(a.getId()).orElseThrow(() -> new RecordNotFoundException("response action", a.getId()));
        a.validate();
        if (a.getIncidentId() != old.getIncidentId()) {
            throw new ValidationException("incidentId", "an action cannot be moved to another incident; delete it and add a new one");
        }
        users.requireStaff(a.getPerformedBy(), "performedBy");
        update(a);
        return findById(a.getId()).orElse(a);
    }

    /** Delete one action. */
    public void AP25110090048_action_delete(int id) throws SQLException, AgroCtiException {
        if (!delete(id)) throw new RecordNotFoundException("response action", id);
    }

    // ------------------------------------------------------------------ CrudModule (web API)
    @Override public String key() { return "actions"; }

    @Override
    public String list(String search, String sort, boolean desc) throws SQLException {
        return Json.array(AP25110090048_action_retrive(search, sort, desc));
    }

    private static ResponseAction fromForm(Form f) throws ValidationException {
        return new ResponseAction(f.integer("incidentId"), f.choice("type", ActionType.class), f.text("description"),
                f.optionalInteger("performedBy"), f.choice("status", ActionStatus.class), f.text("dueDate"));
    }

    @Override
    public String create(Form f, UserAccount by) throws SQLException, AgroCtiException {
        ResponseAction a = fromForm(f);
        if (a.getPerformedBy() == null && by != null) a.setPerformedBy(by.getId());
        return AP25110090048_action_create(a).toJson();
    }

    @Override
    public String update(String id, Form f, UserAccount by) throws SQLException, AgroCtiException {
        ResponseAction a = fromForm(f);
        a.setId(CrudModule.intId(id));
        return AP25110090048_action_update(a).toJson();
    }

    @Override
    public String remove(String id, UserAccount by) throws SQLException, AgroCtiException {
        int n = CrudModule.intId(id);
        AP25110090048_action_delete(n);
        return "response action " + n + " deleted";
    }

    @Override
    public String options() throws SQLException {
        return CrudModule.optionsJson(findAll());
    }
}
