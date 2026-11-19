package com.agrocti.module;

import com.agrocti.config.Config;
import com.agrocti.db.CrudRepository;
import com.agrocti.db.Database;
import com.agrocti.exception.AgroCtiException;
import com.agrocti.exception.RecordNotFoundException;
import com.agrocti.exception.ValidationException;
import com.agrocti.model.FarmNode;
import com.agrocti.model.NodeStatus;
import com.agrocti.model.UserAccount;
import com.agrocti.util.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Module 1 - Farm node registry (M. Sai Chandogya, AP25110090046).
 * Table: farm_nodes. The 24 nodes of the monitored network are created at first start;
 * more nodes can be registered, edited and removed from the browser.
 */
public class AgroCTI_FarmNode extends CrudRepository<FarmNode, Integer> implements CrudModule {

    private static final String[] CROPS = {"Paddy", "Cotton", "Chilli", "Maize", "Groundnut", "Turmeric"};

    @Override protected String table() { return "farm_nodes"; }

    @Override
    protected String selectSql() {
        return "SELECT t.*, u.full_name AS owner_name FROM farm_nodes t LEFT JOIN users u ON u.id = t.owner_id";
    }

    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("id", "t.id", "code", "t.node_code", "region", "t.region", "crop", "t.crop",
                "gatewayIp", "INET_ATON(t.gateway_ip)", "firmware", "t.firmware", "status", "t.status", "ownerName", "owner_name");
    }

    @Override
    protected List<String> searchColumns() {
        return List.of("t.node_code", "t.region", "t.crop", "t.gateway_ip", "t.firmware", "t.status", "u.full_name");
    }

    @Override protected boolean isNew(FarmNode n) { return n.getId() == 0; }

    @Override
    protected FarmNode map(ResultSet rs) throws SQLException {
        FarmNode n = new FarmNode(nullableInt(rs, "sim_index"), rs.getString("node_code"), rs.getString("region"),
                rs.getString("crop"), rs.getString("gateway_ip"), rs.getString("firmware"),
                NodeStatus.valueOf(rs.getString("status")), nullableInt(rs, "owner_id"));
        n.setId(rs.getInt("id"));
        n.setOwnerName(rs.getString("owner_name"));
        n.setCreatedAt(text(rs, "created_at"));
        return n;
    }

    @Override
    protected FarmNode insert(FarmNode n) throws SQLException {
        String sql = "INSERT INTO farm_nodes (sim_index, node_code, region, crop, gateway_ip, firmware, status, owner_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setNullableInt(ps, 1, n.getSimIndex());
            ps.setString(2, n.getCode());
            ps.setString(3, n.getRegion());
            ps.setString(4, n.getCrop());
            ps.setString(5, n.getGatewayIp());
            ps.setString(6, n.getFirmware());
            ps.setString(7, n.getStatus().name());
            setNullableInt(ps, 8, n.getOwnerId());
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                if (k.next()) n.setId(k.getInt(1));
            }
        }
        return n;
    }

    @Override
    protected void update(FarmNode n) throws SQLException {
        String sql = "UPDATE farm_nodes SET node_code = ?, region = ?, crop = ?, gateway_ip = ?, firmware = ?, status = ?,"
                + " owner_id = ? WHERE id = ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, n.getCode());
            ps.setString(2, n.getRegion());
            ps.setString(3, n.getCrop());
            ps.setString(4, n.getGatewayIp());
            ps.setString(5, n.getFirmware());
            ps.setString(6, n.getStatus().name());
            setNullableInt(ps, 7, n.getOwnerId());
            ps.setInt(8, n.getId());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ CRUD with the course naming convention
    /** Create: registers a new node after validation; node codes must be unique. */
    public FarmNode AP25110090046_farmnode_create(FarmNode n) throws SQLException, ValidationException {
        n.validate();
        n.setSimIndex(null);                  // nodes added by hand are not part of the simulated sensor network
        try {
            return insert(n);
        } catch (SQLIntegrityConstraintViolationException e) {
            throw duplicateOrOwner(n, e);
        }
    }

    /** Retrieve: search and sort the registry. */
    public List<FarmNode> AP25110090046_farmnode_retrive(String search, String sort, boolean desc) throws SQLException {
        return search(search, sort, desc);
    }

    /** Update: nodes of the monitored network keep their code and region, because the sensors report under them. */
    public FarmNode AP25110090046_farmnode_update(FarmNode n) throws SQLException, AgroCtiException {
        FarmNode old = findById(n.getId()).orElseThrow(() -> new RecordNotFoundException("farm node", n.getId()));
        n.validate();
        n.setSimIndex(old.getSimIndex());
        if (old.isMonitored() && (!old.getCode().equals(n.getCode()) || !old.getRegion().equals(n.getRegion()))) {
            throw new ValidationException("code", old.getCode() + " is a monitored node: its code and region cannot change");
        }
        try {
            update(n);
        } catch (SQLIntegrityConstraintViolationException e) {
            throw duplicateOrOwner(n, e);
        }
        return n;
    }

    /** Delete: monitored nodes cannot be deleted (set them to RETIRED instead). */
    public void AP25110090046_farmnode_delete(int id) throws SQLException, AgroCtiException {
        FarmNode n = findById(id).orElseThrow(() -> new RecordNotFoundException("farm node", id));
        if (n.isMonitored()) {
            throw new ValidationException(null, n.getCode() + " belongs to the monitored network and has sensor history;"
                    + " set its status to RETIRED instead of deleting it");
        }
        delete(id);
    }

    private static ValidationException duplicateOrOwner(FarmNode n, SQLIntegrityConstraintViolationException e) {
        if (e.getMessage() != null && e.getMessage().contains("fk_node_owner")) {
            return new ValidationException("ownerId", "the chosen owner does not exist");
        }
        return new ValidationException("code", "a node with code " + n.getCode() + " already exists");
    }

    /** Creates the 24 monitored nodes the first time the program runs. */
    public void seedNetwork(Integer farmerId) throws SQLException {
        if (count() > 0) return;
        for (int i = 0; i < Config.NODES; i++) {
            insert(new FarmNode(i, Config.nodeName(i), Config.REGIONS[Config.regionOf(i)], CROPS[(i * 5 + 1) % CROPS.length],
                    Config.gatewayIp(i), i % 7 == 3 ? "v1.3.9" : "v1.4.2", NodeStatus.ACTIVE, i % 3 == 0 ? farmerId : null));
        }
    }

    /** Nodes per status, computed with a Stream. */
    public Map<NodeStatus, Long> countByStatus() throws SQLException {
        return findAll().stream().collect(Collectors.groupingBy(FarmNode::getStatus, Collectors.counting()));
    }

    // ------------------------------------------------------------------ CrudModule (web API)
    @Override public String key() { return "nodes"; }

    @Override
    public String list(String search, String sort, boolean desc) throws SQLException {
        return Json.array(AP25110090046_farmnode_retrive(search, sort, desc));
    }

    private static FarmNode fromForm(Form f) throws ValidationException {
        return new FarmNode(null, f.text("code"), f.text("region"), f.text("crop"), f.text("gatewayIp"),
                f.text("firmware"), f.choice("status", NodeStatus.class), f.optionalInteger("ownerId"));
    }

    @Override
    public String create(Form f, UserAccount by) throws SQLException, AgroCtiException {
        return AP25110090046_farmnode_create(fromForm(f)).toJson();
    }

    @Override
    public String update(String id, Form f, UserAccount by) throws SQLException, AgroCtiException {
        FarmNode n = fromForm(f);
        n.setId(CrudModule.intId(id));
        return AP25110090046_farmnode_update(n).toJson();
    }

    @Override
    public String remove(String id, UserAccount by) throws SQLException, AgroCtiException {
        int n = CrudModule.intId(id);
        AP25110090046_farmnode_delete(n);
        return "farm node " + n + " deleted";
    }

    /**
     * Only monitored nodes can have incidents, so only they are offered in the incident form.
     * The value is the node's index in the sensor network (incidents.node_id refers to farm_nodes.sim_index).
     */
    @Override
    public String options() throws SQLException {
        return "[" + findAll().stream().filter(FarmNode::isMonitored)
                .map(n -> Json.object().put("id", String.valueOf(n.getSimIndex())).put("label", n.label()).build())
                .collect(Collectors.joining(",")) + "]";
    }
}
