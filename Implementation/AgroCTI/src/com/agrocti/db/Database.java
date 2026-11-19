package com.agrocti.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Connects to MySQL through JDBC (MySQL Connector/J), creates the tables and hands out connections.
 * The URL, user and password come from db.properties; environment variables can override them.
 */
public final class Database {

    private static String url;
    private static String user;
    private static String password;

    private Database() { }

    /** Reads the connection settings, checks that MySQL answers and creates any missing table. */
    public static synchronized void init(Path propsFile, boolean testDatabase) throws SQLException {
        Properties p = new Properties();
        if (Files.exists(propsFile)) {
            try (InputStream in = Files.newInputStream(propsFile)) {
                p.load(in);
            } catch (IOException e) {
                throw new SQLException("cannot read " + propsFile + ": " + e.getMessage(), e);
            }
        }
        String key = testDatabase ? "db.test.url" : "db.url";
        url = setting(testDatabase ? "AGROCTI_DB_TEST_URL" : "AGROCTI_DB_URL", p.getProperty(key));
        user = setting("AGROCTI_DB_USER", p.getProperty("db.user", "agrocti"));
        password = setting("AGROCTI_DB_PASSWORD", p.getProperty("db.password", ""));
        if (url == null) throw new SQLException(key + " is missing in " + propsFile);
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC driver not found - is lib/mysql-connector-j-9.1.0.jar on the class path?", e);
        }
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            for (String ddl : Schema.TABLES) s.executeUpdate(ddl);
        } catch (SQLException e) {
            throw explain(e);
        }
    }

    private static String setting(String env, String fallback) {
        String v = System.getenv(env);
        return v != null && !v.isBlank() ? v : fallback;
    }

    public static Connection getConnection() throws SQLException {
        if (url == null) throw new SQLException("Database.init() has not been called");
        return DriverManager.getConnection(url, user, password);
    }

    /** Address shown in logs, without the password. */
    public static String describe() {
        return url == null ? "(not connected)" : url.replaceAll("\\?.*", "") + " as " + user;
    }

    /** Drops every table (children first). Only used by the self-tests on the test database. */
    public static void dropAll() throws SQLException {
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            for (String t : new String[]{"response_actions", "incidents", "threat_indicators", "farm_nodes", "model_runs", "users"}) {
                s.executeUpdate("DROP TABLE IF EXISTS " + t);
            }
            for (String ddl : Schema.TABLES) s.executeUpdate(ddl);
        }
    }

    /** Turns the two most common start-up problems into a message that says what to do. */
    public static SQLException explain(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.startsWith("08")) {
            return new SQLException("cannot reach MySQL (" + describe() + "). Start the MySQL server, then run again.", state, e);
        }
        if ("28000".equals(state) || e.getErrorCode() == 1045) {
            return new SQLException("MySQL refused user '" + user + "'. Run sql/setup.sql once as root, or put your own"
                    + " MySQL user and password in db.properties.", state, e);
        }
        if (e.getErrorCode() == 1049) {
            return new SQLException("the MySQL database does not exist yet. Run: mysql -u root -p < sql/setup.sql", state, e);
        }
        return e;
    }
}
