package com.agrocti.model;

import com.agrocti.exception.ValidationException;
import com.agrocti.util.Json;

/**
 * A person who can log in to the dashboard (module 5: user accounts and login).
 * The password itself is never stored, only a salted PBKDF2 hash of it.
 */
public class UserAccount extends Record<Integer> {

    private int id;
    private String username;
    private String fullName;
    private Role role = Role.FARMER;
    private String passwordHash = "";
    private String salt = "";
    private String newPassword;      // only set while creating or changing the password

    public UserAccount() { }

    public UserAccount(String username, String fullName, Role role, String newPassword) {
        this.username = username;
        this.fullName = fullName;
        this.role = role;
        this.newPassword = newPassword;
    }

    @Override
    public void validate() throws ValidationException {
        username = requireText("username", username, 40).toLowerCase();
        requireMatch("username", username, "[a-z][a-z0-9_.]{2,39}", "ravi_k (3-40 letters, digits, _ or ., starting with a letter)");
        fullName = requireText("fullName", fullName, 80);
        if (role == null) throw new ValidationException("role", "role is required");
        if (newPassword != null) checkPassword(newPassword);
    }

    /** Password rule: at least 8 characters with at least one letter and one digit. */
    public static void checkPassword(String p) throws ValidationException {
        if (p.length() < 8 || !p.matches(".*[A-Za-z].*") || !p.matches(".*\\d.*")) {
            throw new ValidationException("password", "password must have at least 8 characters, including a letter and a digit");
        }
    }

    @Override
    public String label() {
        return fullName + " (" + role.name().toLowerCase() + ")";
    }

    @Override
    public Integer getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getPasswordHash() { return passwordHash; }
    public String getSalt() { return salt; }
    public void setPassword(String hash, String salt) {
        this.passwordHash = hash;
        this.salt = salt;
    }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }

    /** The hash and salt are deliberately left out. */
    @Override
    public String toJson() {
        return Json.object().put("id", id).put("username", username).put("fullName", fullName)
                .put("role", role.name()).put("createdAt", getCreatedAt()).build();
    }
}
