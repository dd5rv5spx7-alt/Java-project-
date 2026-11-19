package com.agrocti.server;

import com.agrocti.model.UserAccount;
import com.agrocti.util.PasswordHasher;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logged-in sessions, kept in memory. The browser holds only a random token in an HttpOnly cookie.
 * ConcurrentHashMap because many HTTP threads read and write it at the same time.
 */
public class SessionManager {

    public static final String COOKIE = "AGROCTI_SESSION";
    private static final Duration LIFETIME = Duration.ofHours(8);
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK = Duration.ofSeconds(30);

    private record Session(UserAccount user, Instant expires) { }

    private record Failures(int count, Instant lockedUntil) { }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public String open(UserAccount user) {
        String token = PasswordHasher.newToken();
        sessions.put(token, new Session(user, Instant.now().plus(LIFETIME)));
        failures.remove(user.getUsername());
        return token;
    }

    public Optional<UserAccount> find(String token) {
        if (token == null) return Optional.empty();
        Session s = sessions.get(token);
        if (s == null) return Optional.empty();
        if (s.expires().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(s.user());
    }

    public void close(String token) {
        if (token != null) sessions.remove(token);
    }

    /** Logs out every session of a user, e.g. after the account was deleted or changed. */
    public void closeAll(int userId) {
        sessions.values().removeIf(s -> s.user().getId() == userId);
    }

    /** After 5 wrong passwords in a row the username is locked for 30 seconds. */
    public boolean isLocked(String username) {
        Failures f = failures.get(username);
        return f != null && f.lockedUntil() != null && f.lockedUntil().isAfter(Instant.now());
    }

    public void recordFailure(String username) {
        failures.compute(username, (k, f) -> {
            int n = (f == null || (f.lockedUntil() != null && f.lockedUntil().isBefore(Instant.now()))) ? 1 : f.count() + 1;
            return new Failures(n, n >= MAX_FAILURES ? Instant.now().plus(LOCK) : null);
        });
    }
}
