package com.agrocti.module;

import com.agrocti.exception.ValidationException;
import com.agrocti.model.Role;
import com.agrocti.model.ThreatIndicator;
import com.agrocti.model.UserAccount;
import com.agrocti.util.Log;

import java.sql.SQLException;
import java.util.List;

/** Fills an empty database with the demo accounts, the 24 monitored farm nodes and the external threat feed. */
public final class DataSeeder {

    /** Demo accounts created on first start (change the passwords in the Users page). */
    public static final String[][] DEMO_USERS = {
        {"admin", "System Administrator", "ADMIN", "Admin@2026"},
        {"analyst", "Security Analyst", "ANALYST", "Analyst@2026"},
        {"farmer", "Ravi Kumar", "FARMER", "Farmer@2026"},
    };

    private DataSeeder() { }

    public static void seed(List<ThreatIndicator> feed) throws SQLException {
        AgroCTI_User users = new AgroCTI_User();
        if (users.count() == 0) {
            for (String[] u : DEMO_USERS) {
                try {
                    users.AP25110090060_user_create(new UserAccount(u[0], u[1], Role.valueOf(u[2]), u[3]));
                } catch (ValidationException e) {
                    throw new IllegalStateException("demo user is invalid: " + e.getMessage(), e);
                }
            }
            Log.info("Created demo accounts admin / analyst / farmer (passwords in README)");
        }
        Integer farmer = users.findAll().stream().filter(u -> u.getRole() == Role.FARMER)
                .map(UserAccount::getId).findFirst().orElse(null);
        AgroCTI_FarmNode nodes = new AgroCTI_FarmNode();
        if (nodes.count() == 0) {
            nodes.seedNetwork(farmer);
            Log.info("Registered the 24 monitored farm nodes");
        }
        if (new AgroCTI_ThreatIndicator().seedIfEmpty(feed)) {
            Log.info("Loaded " + feed.size() + " threat indicators from the feed file into MySQL");
        }
    }
}
