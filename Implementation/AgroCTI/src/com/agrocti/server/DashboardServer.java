package com.agrocti.server;

import com.agrocti.config.Config;
import com.agrocti.cti.ThreatFeed;
import com.agrocti.exception.AccessDeniedException;
import com.agrocti.exception.AgroCtiException;
import com.agrocti.exception.RecordNotFoundException;
import com.agrocti.exception.ValidationException;
import com.agrocti.model.UserAccount;
import com.agrocti.module.AgroCTI_FarmNode;
import com.agrocti.module.AgroCTI_Incident;
import com.agrocti.module.AgroCTI_ResponseAction;
import com.agrocti.module.AgroCTI_ThreatIndicator;
import com.agrocti.module.AgroCTI_User;
import com.agrocti.module.CrudModule;
import com.agrocti.module.Form;
import com.agrocti.util.Json;
import com.agrocti.util.JsonParser;
import com.agrocti.util.Log;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Serves the web pages and the JSON API with the JDK's built-in HTTP server.
 * Every /api call except /api/login needs a logged-in session; writes also need the right role.
 */
public class DashboardServer {

    private static final int MAX_BODY = 64 * 1024;

    private final HttpServer server;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final LiveSimulator sim;
    private final ThreatFeed feed;
    private final Path webDir;
    private final Path resultsFile;
    private final AgroCTI_Incident incidents;
    private final AgroCTI_ThreatIndicator indicators;
    private final AgroCTI_User users = new AgroCTI_User();
    private final SessionManager sessions = new SessionManager();
    /** The five record modules, looked up by the name in the URL. */
    private final Map<String, CrudModule> modules = new LinkedHashMap<>();

    /** Thrown inside a handler to send an error status with a message. */
    private static final class HttpError extends Exception {
        final int status;
        final String field;

        HttpError(int status, String msg) {
            this(status, msg, null);
        }

        HttpError(int status, String msg, String field) {
            super(msg);
            this.status = status;
            this.field = field;
        }
    }

    /** One request: method, path parts, query, body and the logged-in user. */
    private record Request(HttpExchange ex, String method, String path, Map<String, String> query, UserAccount user) { }

    public DashboardServer(int port, LiveSimulator sim, ThreatFeed feed, Path webDir, Path resultsFile,
                           AgroCTI_Incident incidents, AgroCTI_ThreatIndicator indicators) throws IOException {
        this.sim = sim;
        this.feed = feed;
        this.webDir = webDir.toAbsolutePath().normalize();
        this.resultsFile = resultsFile;
        this.incidents = incidents;
        this.indicators = indicators;
        Stream.<CrudModule>of(new AgroCTI_FarmNode(), indicators, incidents, new AgroCTI_ResponseAction(), users)
              .forEach(m -> modules.put(m.key(), m));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(pool);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
        Log.info("Dashboard running at http://localhost:" + server.getAddress().getPort() + "  (log in as admin / Admin@2026)");
    }

    public void stop() {
        server.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        String type = "application/json; charset=utf-8";
        byte[] body;
        try {
            URI uri = ex.getRequestURI();
            String path = uri.getPath();
            if (path.startsWith("/api/")) {
                Optional<UserAccount> user = sessions.find(cookie(ex));
                Request r = new Request(ex, ex.getRequestMethod(), path, query(uri.getRawQuery()), user.orElse(null));
                body = api(r).getBytes(StandardCharsets.UTF_8);
                if ("POST".equals(r.method()) && path.equals("/api/records/" + path.substring(path.lastIndexOf('/') + 1))) status = 201;
            } else {
                String name = path.equals("/") ? "index.html" : path.substring(1);
                Path file = webDir.resolve(name).normalize();
                if (!file.startsWith(webDir) || !Files.isRegularFile(file)) throw new HttpError(404, "not found: " + path);
                body = Files.readAllBytes(file);
                type = contentType(file.getFileName().toString());
            }
        } catch (HttpError e) {
            status = e.status;
            Json.Builder b = Json.object().put("error", e.getMessage());
            if (e.field != null) b.put("field", e.field);
            body = b.build().getBytes(StandardCharsets.UTF_8);
        } catch (SQLException e) {
            status = 500;
            Log.warn("database error: " + e.getMessage());
            body = Json.object().put("error", "database error: " + e.getMessage()).build().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            status = 500;
            Log.warn("request failed: " + e);
            body = Json.object().put("error", "internal error").build().getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head ? -1 : body.length);
        try (OutputStream os = ex.getResponseBody()) {
            if (!head) os.write(body);
        }
    }

    // ------------------------------------------------------------------ API
    private String api(Request r) throws HttpError, SQLException, IOException {
        if (r.path().equals("/api/login")) return login(r);
        if (r.user() == null) throw new HttpError(401, "please log in");
        String p = r.path();
        if (p.startsWith("/api/records/")) return records(r, p.substring("/api/records/".length()));
        if (p.startsWith("/api/options/")) return module(p.substring("/api/options/".length())).options();
        switch (p) {
            case "/api/logout":
                requirePost(r);
                sessions.close(cookie(r.ex()));
                r.ex().getResponseHeaders().add("Set-Cookie", SessionManager.COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
                return Json.object().put("ok", true).build();
            case "/api/me":
                return Json.object().raw("user", r.user().toJson())
                        .put("canEdit", r.user().getRole().canEditRecords())
                        .put("canManageUsers", r.user().getRole().canManageUsers()).build();
            case "/api/state":
                return sim.stateJson();
            case "/api/series": {
                int node = intParam(r.query(), "node", 0);
                if (node < 0 || node >= Config.NODES) throw new HttpError(400, "node must be 0.." + (Config.NODES - 1));
                return sim.seriesJson(node);
            }
            case "/api/incidents": {
                int limit = intParam(r.query(), "limit", 40);
                if (limit < 1 || limit > 500) throw new HttpError(400, "limit must be 1..500");
                return Json.array(incidents.latest(limit));
            }
            case "/api/results":
                if (!Files.exists(resultsFile)) throw new HttpError(404, "no results yet - run the evaluation first");
                return Files.readString(resultsFile);
            case "/api/feed":
                return Json.object().raw("external", Json.array(feed.all())).raw("local", Json.array(indicators.local())).build();
            case "/api/control": {
                requirePost(r);
                String action = r.query().getOrDefault("action", "");
                switch (action) {
                    case "pause": sim.setPaused(true); break;
                    case "resume": sim.setPaused(false); break;
                    case "faster": sim.setDelay(sim.getDelay() / 2); break;
                    case "slower": sim.setDelay(sim.getDelay() * 2); break;
                    default: throw new HttpError(400, "unknown action '" + action + "'");
                }
                return sim.stateJson();
            }
            default:
                throw new HttpError(404, "unknown API " + p);
        }
    }

    private String login(Request r) throws HttpError, SQLException, IOException {
        requirePost(r);
        Form f = new Form(body(r.ex()));
        String username = f.text("username").toLowerCase();
        if (username.isEmpty() || !f.has("password")) throw new HttpError(400, "enter your username and password");
        if (sessions.isLocked(username)) throw new HttpError(429, "too many wrong passwords - wait 30 seconds and try again");
        Optional<UserAccount> u = users.authenticate(username, f.text("password"));
        if (u.isEmpty()) {
            sessions.recordFailure(username);
            Log.warn("failed login for '" + username + "'");
            throw new HttpError(401, "wrong username or password");
        }
        String token = sessions.open(u.get());
        r.ex().getResponseHeaders().add("Set-Cookie", SessionManager.COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
        Log.info("user '" + username + "' logged in");
        return Json.object().raw("user", u.get().toJson()).build();
    }

    /** GET list, POST create, PUT update, DELETE delete - for every module through the CrudModule interface. */
    private String records(Request r, String rest) throws HttpError, SQLException, IOException {
        String[] parts = rest.split("/", 2);
        CrudModule m = module(parts[0]);
        String id = parts.length > 1 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : null;
        try {
            if ("GET".equals(r.method()) && id == null) {
                if (!m.canRead(r.user().getRole())) throw new AccessDeniedException("only an administrator may list " + m.key());
                String dir = r.query().getOrDefault("dir", "asc");
                return m.list(r.query().get("q"), r.query().get("sort"), "desc".equals(dir));
            }
            if (!m.canWrite(r.user().getRole())) {
                throw new AccessDeniedException("your role (" + r.user().getRole() + ") may only view " + m.key());
            }
            switch (r.method()) {
                case "POST":
                    if (id != null) throw new HttpError(405, "POST to /api/records/" + m.key());
                    return audit(r, "created", m.key(), m.create(new Form(body(r.ex())), r.user()));
                case "PUT":
                    if (id == null) throw new HttpError(400, "the record id is missing");
                    String saved = m.update(id, new Form(body(r.ex())), r.user());
                    if (m == users) sessions.closeAll(Integer.parseInt(id) == r.user().getId() ? -1 : Integer.parseInt(id));
                    return audit(r, "updated", m.key() + "/" + id, saved);
                case "DELETE":
                    if (id == null) throw new HttpError(400, "the record id is missing");
                    String msg = m.remove(id, r.user());
                    if (m == users) sessions.closeAll(Integer.parseInt(id));
                    audit(r, "deleted", m.key() + "/" + id, "");
                    return Json.object().put("message", msg).build();
                default:
                    throw new HttpError(405, "method " + r.method() + " is not allowed");
            }
        } catch (ValidationException e) {
            throw new HttpError(400, e.getMessage(), e.getField());
        } catch (RecordNotFoundException e) {
            throw new HttpError(404, e.getMessage());
        } catch (AccessDeniedException e) {
            throw new HttpError(403, e.getMessage());
        } catch (AgroCtiException | IllegalArgumentException e) {
            throw new HttpError(400, e.getMessage());
        }
    }

    private static String audit(Request r, String what, String target, String json) {
        Log.info(r.user().getUsername() + " " + what + " " + target);
        return json;
    }

    private CrudModule module(String key) throws HttpError {
        CrudModule m = modules.get(key);
        if (m == null) throw new HttpError(404, "unknown module '" + key + "'; use one of " + modules.keySet());
        return m;
    }

    // ------------------------------------------------------------------ helpers
    private static void requirePost(Request r) throws HttpError {
        if (!"POST".equals(r.method())) throw new HttpError(405, "use POST");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpExchange ex) throws HttpError, IOException {
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) {
            raw = in.readNBytes(MAX_BODY + 1);
        }
        if (raw.length > MAX_BODY) throw new HttpError(413, "request too large");
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return new HashMap<>();
        try {
            Object o = JsonParser.parse(text);
            if (!(o instanceof Map)) throw new HttpError(400, "the request body must be a JSON object");
            return (Map<String, Object>) o;
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, "bad JSON: " + e.getMessage());
        }
    }

    private static String cookie(HttpExchange ex) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers) {
            for (String part : h.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(SessionManager.COOKIE)) return kv[1];
            }
        }
        return null;
    }

    private static int intParam(Map<String, String> q, String k, int def) throws HttpError {
        String v = q.get(k);
        if (v == null) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new HttpError(400, "'" + k + "' must be a whole number");
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i);
            String v = i < 0 ? "" : part.substring(i + 1);
            m.put(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8), java.net.URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }
}
