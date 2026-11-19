package com.agrocti.module;

import com.agrocti.db.CrudRepository;
import com.agrocti.db.Database;
import com.agrocti.exception.AccessDeniedException;
import com.agrocti.exception.AgroCtiException;
import com.agrocti.exception.RecordNotFoundException;
import com.agrocti.exception.ValidationException;
import com.agrocti.model.Role;
import com.agrocti.model.UserAccount;
import com.agrocti.util.Json;
import com.agrocti.util.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Module 5 - User accounts and login (Sarwan, AP25110090060).
 * Table: users. Only an ADMIN may create, change or delete accounts.
 */
public class AgroCTI_User extends CrudRepository<UserAccount, Integer> implements CrudModule {

    @Override protected String table() { return "users"; }

    @Override protected String selectSql() { return "SELECT t.* FROM users t"; }

    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("id", "t.id", "username", "t.username", "fullName", "t.full_name", "role", "t.role", "createdAt", "t.created_at");
    }

    @Override protected List<String> searchColumns() { return List.of("t.username", "t.full_name", "t.role"); }

    @Override protected boolean isNew(UserAccount u) { return u.getId() == 0; }

    @Override
    protected UserAccount map(ResultSet rs) throws SQLException {
        UserAccount u = new UserAccount(rs.getString("username"), rs.getString("full_name"), Role.valueOf(rs.getString("role")), null);
        u.setId(rs.getInt("id"));
        u.setPassword(rs.getString("password_hash"), rs.getString("salt"));
        u.setCreatedAt(text(rs, "created_at"));
        return u;
    }

    @Override
    protected UserAccount insert(UserAccount u) throws SQLException {
        String salt = PasswordHasher.newSalt();
        u.setPassword(PasswordHasher.hash(u.getNewPassword(), salt), salt);
        String sql = "INSERT INTO users (username, full_name, role, password_hash, salt) VALUES (?, ?, ?, ?, ?)";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, u.getUsername());
            ps.setString(2, u.getFullName());
            ps.setString(3, u.getRole().name());
            ps.setString(4, u.getPasswordHash());
            ps.setString(5, u.getSalt());
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                if (k.next()) u.setId(k.getInt(1));
            }
        }
        u.setNewPassword(null);
        return u;
    }

    @Override
    protected void update(UserAccount u) throws SQLException {
        if (u.getNewPassword() != null) {
            String salt = PasswordHasher.newSalt();
            u.setPassword(PasswordHasher.hash(u.getNewPassword(), salt), salt);
        }
        String sql = "UPDATE users SET username = ?, full_name = ?, role = ?, password_hash = ?, salt = ? WHERE id = ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, u.getUsername());
            ps.setString(2, u.getFullName());
            ps.setString(3, u.getRole().name());
            ps.setString(4, u.getPasswordHash());
            ps.setString(5, u.getSalt());
            ps.setInt(6, u.getId());
            ps.executeUpdate();
        }
        u.setNewPassword(null);
    }

    // ------------------------------------------------------------------ CRUD with the course naming convention
    /** Create: validates the account, hashes the password and inserts it. */
    public UserAccount AP25110090060_user_create(UserAccount u) throws SQLException, ValidationException {
        if (u.getNewPassword() == null || u.getNewPassword().isEmpty()) throw new ValidationException("password", "password is required");
        u.validate();
        try {
            return insert(u);
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new ValidationException("username", "the username '" + u.getUsername() + "' is already taken");
        }
    }

    /** Retrieve: search by username, name or role, sorted by any listed column. */
    public List<UserAccount> AP25110090060_user_retrive(String search, String sort, boolean desc) throws SQLException {
        return search(search, sort, desc);
    }

    /** Update: name, role and (optionally) password. The last administrator cannot be demoted. */
    public UserAccount AP25110090060_user_update(UserAccount u) throws SQLException, AgroCtiException {
        UserAccount old = findById(u.getId()).orElseThrow(() -> new RecordNotFoundException("user", u.getId()));
        u.validate();
        if (old.getRole() == Role.ADMIN && u.getRole() != Role.ADMIN && countAdmins() <= 1) {
            throw new ValidationException("role", "this is the only administrator - make another user ADMIN first");
        }
        if (u.getNewPassword() == null) u.setPassword(old.getPasswordHash(), old.getSalt());
        try {
            update(u);
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new ValidationException("username", "the username '" + u.getUsername() + "' is already taken");
        }
        return u;
    }

    /** Delete: a user cannot delete their own account or the last administrator. */
    public void AP25110090060_user_delete(int id, UserAccount by) throws SQLException, AgroCtiException {
        UserAccount u = findById(id).orElseThrow(() -> new RecordNotFoundException("user", id));
        if (by != null && by.getId() == id) throw new ValidationException(null, "you cannot delete your own account while logged in");
        if (u.getRole() == Role.ADMIN && countAdmins() <= 1) throw new ValidationException(null, "the last administrator cannot be deleted");
        delete(id);
    }

    // ------------------------------------------------------------------ login
    /** Returns the account if the username exists and the password is right. */
    public Optional<UserAccount> authenticate(String username, String password) throws SQLException {
        if (username == null || password == null) return Optional.empty();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(selectSql() + " WHERE t.username = ?")) {
            ps.setString(1, username.trim().toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    PasswordHasher.hash(password, PasswordHasher.newSalt());   // same time as a wrong password
                    return Optional.empty();
                }
                UserAccount u = map(rs);
                return PasswordHasher.matches(password, u.getSalt(), u.getPasswordHash()) ? Optional.of(u) : Optional.empty();
            }
        }
    }

    /** Counts administrators with a Java Stream over all accounts. */
    private long countAdmins() throws SQLException {
        return findAll().stream().filter(u -> u.getRole() == Role.ADMIN).count();
    }

    /** Number of accounts per role, e.g. {ADMIN=1, ANALYST=2}. */
    public Map<Role, Long> countByRole() throws SQLException {
        return findAll().stream().collect(Collectors.groupingBy(UserAccount::getRole, Collectors.counting()));
    }

    // ------------------------------------------------------------------ CrudModule (web API)
    @Override public String key() { return "users"; }

    /** Only administrators see the account list (the drop-down of names is open to everyone). */
    @Override
    public boolean canRead(Role role) {
        return role.canManageUsers();
    }

    @Override
    public boolean canWrite(Role role) {
        return role.canManageUsers();
    }

    @Override
    public String list(String search, String sort, boolean desc) throws SQLException {
        return Json.array(AP25110090060_user_retrive(search, sort, desc));
    }

    private static UserAccount fromForm(Form f) throws ValidationException {
        UserAccount u = new UserAccount(f.text("username"), f.text("fullName"), f.choice("role", Role.class), null);
        if (f.has("password")) u.setNewPassword(f.text("password"));
        return u;
    }

    @Override
    public String create(Form f, UserAccount by) throws SQLException, AgroCtiException {
        return AP25110090060_user_create(fromForm(f)).toJson();
    }

    @Override
    public String update(String id, Form f, UserAccount by) throws SQLException, AgroCtiException {
        UserAccount u = fromForm(f);
        u.setId(CrudModule.intId(id));
        return AP25110090060_user_update(u).toJson();
    }

    @Override
    public String remove(String id, UserAccount by) throws SQLException, AgroCtiException {
        int n = CrudModule.intId(id);
        AP25110090060_user_delete(n, by);
        return "user " + n + " deleted";
    }

    @Override
    public String options() throws SQLException {
        return CrudModule.optionsJson(findAll());
    }

    /** Only analysts and administrators can be assigned incidents or response actions. */
    public void requireStaff(Integer userId, String field) throws SQLException, ValidationException {
        if (userId == null) return;
        Optional<UserAccount> u = findById(userId);
        if (u.isEmpty()) throw new ValidationException(field, "user " + userId + " does not exist");
        if (!u.get().getRole().canEditRecords()) throw new ValidationException(field, u.get().getFullName() + " is a farmer account and cannot be assigned work");
    }

    /** Thrown on purpose when a non-admin calls an admin-only operation from code. */
    public static void requireAdmin(UserAccount by) throws AccessDeniedException {
        if (by == null || !by.getRole().canManageUsers()) throw new AccessDeniedException("only an administrator may do this");
    }
}
