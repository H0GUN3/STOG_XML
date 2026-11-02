package com.stog.backend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class AuthService {
    static final long ACCESS_SECONDS = Duration.ofMinutes(15).toSeconds();
    static final long REFRESH_SECONDS = Duration.ofDays(30).toSeconds();
    private static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(90);
    private static final Duration ATTEMPT_RETENTION = Duration.ofHours(24);
    private static final Set<String> RESERVED_USERNAMES = Set.of(
        "admin", "administrator", "root", "stog", "support", "system");
    // PostgreSQL documents finite TIMESTAMPTZ support through 294276 AD.
    private static final Instant POSTGRES_TIMESTAMP_MAX = Instant.parse("+294276-12-31T23:59:59Z");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final KakaoTokenVerifier verifier;
    private final PasswordEncoder passwordEncoder;
    private final PasswordBlocklist passwordBlocklist;
    private final String dummyPasswordHash;
    private final UUID restrictedOwnerUserId;

    AuthService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, KakaoTokenVerifier verifier,
            PasswordEncoder passwordEncoder, PasswordBlocklist passwordBlocklist,
            @Value("${stog.auth.restricted-owner.enabled:false}") boolean restrictedOwnerEnabled,
            @Value("${stog.auth.restricted-owner.user-id:}") String restrictedOwnerUserId) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.verifier = verifier;
        this.passwordEncoder = passwordEncoder;
        this.passwordBlocklist = passwordBlocklist;
        this.dummyPasswordHash = passwordEncoder.encode(token());
        this.restrictedOwnerUserId = restrictedOwner(restrictedOwnerEnabled, restrictedOwnerUserId);
    }

    Tokens signup(String rawUsername, String rawPassword, String rawDisplayName) {
        if (restrictedOwnerUserId != null) throw AuthException.registrationUnavailable();
        String username = username(rawUsername);
        String displayName = displayName(rawDisplayName);
        String password = password(rawPassword);
        if (passwordBlocklist.contains(password, username)) throw AuthException.passwordNotAcceptable();
        String passwordHash = passwordEncoder.encode(password);
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                UUID userId = UUID.randomUUID();
                jdbc.update("INSERT INTO stog_user(id,display_name) VALUES (?,?)", userId, displayName);
                jdbc.update("INSERT INTO auth_password_credential(user_id,username,password_hash,created_at,password_changed_at) "
                        + "VALUES (?,?,?,?,?)",
                    userId, username, passwordHash, at(now), at(now));
                return issueSession(userId, now);
            });
        } catch (DuplicateKeyException exception) {
            throw AuthException.accountNotCreated();
        }
    }

    Tokens login(String rawUsername, String rawPassword) {
        String username = username(rawUsername);
        String password = password(rawPassword);
        List<PasswordCredential> credentials = jdbc.query(
            "SELECT user_id,password_hash FROM auth_password_credential WHERE username=?",
            (result, row) -> new PasswordCredential(result.getObject(1, UUID.class), result.getString(2)), username);
        PasswordCredential credential = credentials.size() == 1 ? credentials.get(0) : null;
        String comparedHash = credential == null ? dummyPasswordHash : credential.passwordHash();
        boolean matches = passwordEncoder.matches(password, comparedHash);
        String upgradedHash = matches && credential != null && passwordEncoder.upgradeEncoding(comparedHash)
            ? passwordEncoder.encode(password) : null;
        LoginOutcome outcome = transactions.execute(status ->
            loginInTransaction(username, credential, matches, upgradedHash));
        if (!outcome.success()) throw AuthException.required();
        return outcome.tokens();
    }

    Tokens exchange(String kakaoToken) {
        KakaoTokenVerifier.VerifiedIdentity identity = verifier.verify(kakaoToken);
        byte[] credentialHash = hash(kakaoToken);
        String access = token();
        String refresh = token();
        return transactions.execute(status -> {
            Instant now = databaseNow();
            Instant credentialExpiry = providerCredentialExpiry(now, identity.credentialExpiresIn());
            jdbc.update("DELETE FROM auth_login_exchange WHERE expires_at<=?", at(now));
            int consumed = jdbc.update("INSERT INTO auth_login_exchange(provider,credential_hash,subject,consumed_at,expires_at) "
                    + "VALUES ('KAKAO',?,?,?,?) ON CONFLICT DO NOTHING",
                credentialHash, identity.subject(), at(now), at(credentialExpiry));
            if (consumed == 0) throw AuthException.required();

            UUID userId;
            if (restrictedOwnerUserId != null) {
                List<UUID> mappedUsers = jdbc.query("SELECT user_id FROM auth_identity WHERE provider='KAKAO' AND subject=?",
                    (result, row) -> result.getObject(1, UUID.class), identity.subject());
                if (mappedUsers.size() != 1 || !restrictedOwnerUserId.equals(mappedUsers.get(0))) {
                    throw AuthException.required();
                }
                userId = mappedUsers.get(0);
            } else {
                UUID candidate = UUID.randomUUID();
                jdbc.update("INSERT INTO stog_user(id) VALUES (?)", candidate);
                int identityInserted = jdbc.update("INSERT INTO auth_identity(provider,subject,user_id,created_at) "
                        + "VALUES ('KAKAO',?,?,?) ON CONFLICT (provider,subject) DO NOTHING",
                    identity.subject(), candidate, at(now));
                if (identityInserted == 1) {
                    userId = candidate;
                } else {
                    jdbc.update("DELETE FROM stog_user WHERE id=?", candidate);
                    userId = jdbc.queryForObject("SELECT user_id FROM auth_identity WHERE provider='KAKAO' AND subject=?",
                        UUID.class, identity.subject());
                }
            }

            return issueSession(userId, now, access, refresh);
        });
    }

    private LoginOutcome loginInTransaction(String username, PasswordCredential credential, boolean matches,
            String upgradedHash) {
        Instant now = databaseNow();
        cleanupAttempts(now);
        boolean blocked = jdbc.query(
            "SELECT blocked_until FROM auth_password_attempt WHERE username=? FOR UPDATE",
            (result, row) -> nullableInstant(result.getObject(1, OffsetDateTime.class)), username).stream()
            .anyMatch(until -> until != null && until.isAfter(now));
        UUID userId = credential == null ? null : credential.userId();
        if (blocked || !matches || userId == null || !ownerAllowed(userId)) {
            recordFailure(username, now);
            return LoginOutcome.failure();
        }
        jdbc.update("DELETE FROM auth_password_attempt WHERE username=?", username);
        if (upgradedHash != null) {
            jdbc.update("UPDATE auth_password_credential SET password_hash=?,password_changed_at=? "
                    + "WHERE user_id=? AND password_hash=?",
                upgradedHash, at(now), userId, credential.passwordHash());
        }
        return LoginOutcome.success(issueSession(userId, now));
    }

    private void cleanupAttempts(Instant now) {
        jdbc.update("DELETE FROM auth_password_attempt WHERE username IN ("
                + "SELECT username FROM auth_password_attempt WHERE updated_at<=? "
                + "ORDER BY updated_at LIMIT 100)",
            at(now.minus(ATTEMPT_RETENTION)));
    }

    private void recordFailure(String username, Instant now) {
        jdbc.update("INSERT INTO auth_password_attempt(username,window_started_at,failure_count,blocked_until,updated_at) "
                + "VALUES (?,?,1,NULL,?) ON CONFLICT (username) DO UPDATE SET "
                + "window_started_at=CASE WHEN auth_password_attempt.window_started_at<=EXCLUDED.updated_at-INTERVAL '15 minutes' "
                + "THEN EXCLUDED.updated_at ELSE auth_password_attempt.window_started_at END,"
                + "failure_count=CASE WHEN auth_password_attempt.window_started_at<=EXCLUDED.updated_at-INTERVAL '15 minutes' "
                + "THEN 1 ELSE LEAST(5,auth_password_attempt.failure_count+1) END,"
                + "blocked_until=CASE "
                + "WHEN auth_password_attempt.blocked_until>EXCLUDED.updated_at THEN auth_password_attempt.blocked_until "
                + "WHEN auth_password_attempt.window_started_at<=EXCLUDED.updated_at-INTERVAL '15 minutes' THEN NULL "
                + "WHEN auth_password_attempt.failure_count+1>=5 THEN EXCLUDED.updated_at+INTERVAL '15 minutes' "
                + "ELSE NULL END,updated_at=EXCLUDED.updated_at",
            username, at(now), at(now));
    }

    private Tokens issueSession(UUID userId, Instant now) {
        return issueSession(userId, now, token(), token());
    }

    private Tokens issueSession(UUID userId, Instant now, String access, String refresh) {
        UUID sessionId = UUID.randomUUID();
        Instant absoluteExpiry = now.plus(ABSOLUTE_LIFETIME);
        Instant accessExpiry = now.plusSeconds(ACCESS_SECONDS);
        Instant refreshExpiry = min(now.plusSeconds(REFRESH_SECONDS), absoluteExpiry);
        jdbc.update("INSERT INTO auth_session(id,user_id,access_token_hash,access_expires_at,created_at,absolute_expires_at) "
                + "VALUES (?,?,?,?,?,?)",
            sessionId, userId, hash(access), at(accessExpiry), at(now), at(absoluteExpiry));
        jdbc.update("INSERT INTO auth_refresh_token(token_hash,session_id,issued_at,expires_at) VALUES (?,?,?,?)",
            hash(refresh), sessionId, at(now), at(refreshExpiry));
        return new Tokens(access, ACCESS_SECONDS, refresh, Duration.between(now, refreshExpiry).toSeconds());
    }

    Tokens refresh(String refreshToken) {
        String nextAccess = token();
        String nextRefresh = token();
        RefreshOutcome outcome = transactions.execute(status -> rotate(hash(refreshToken), nextAccess, nextRefresh));
        if (!outcome.success()) throw AuthException.required();
        return outcome.tokens();
    }

    private RefreshOutcome rotate(byte[] refreshHash, String nextAccess, String nextRefresh) {
        Instant now = databaseNow();
        List<RefreshRow> rows = jdbc.query("SELECT r.session_id,r.expires_at,r.used_at,s.absolute_expires_at,s.revoked_at,s.user_id "
                + "FROM auth_refresh_token r JOIN auth_session s ON s.id=r.session_id "
                + "WHERE r.token_hash=? FOR UPDATE OF r,s",
            (result, row) -> new RefreshRow(result.getObject(1, UUID.class), result.getObject(2, OffsetDateTime.class).toInstant(),
                nullableInstant(result.getObject(3, OffsetDateTime.class)), result.getObject(4, OffsetDateTime.class).toInstant(),
                nullableInstant(result.getObject(5, OffsetDateTime.class)), result.getObject(6, UUID.class)), refreshHash);
        if (rows.isEmpty()) return RefreshOutcome.failure();
        RefreshRow row = rows.get(0);
        if (!ownerAllowed(row.userId())) return RefreshOutcome.failure();
        if (row.usedAt() != null) {
            if (row.revokedAt() == null) jdbc.update("UPDATE auth_session SET revoked_at=? WHERE id=? AND revoked_at IS NULL", at(now), row.sessionId());
            return RefreshOutcome.failure();
        }
        if (row.revokedAt() != null || !row.expiresAt().isAfter(now) || !row.absoluteExpiresAt().isAfter(now)) {
            return RefreshOutcome.failure();
        }

        Instant accessExpiry = min(now.plusSeconds(ACCESS_SECONDS), row.absoluteExpiresAt());
        Instant refreshExpiry = min(now.plusSeconds(REFRESH_SECONDS), row.absoluteExpiresAt());
        jdbc.update("UPDATE auth_refresh_token SET used_at=? WHERE token_hash=?", at(now), refreshHash);
        jdbc.update("UPDATE auth_session SET access_token_hash=?,access_expires_at=? WHERE id=?",
            hash(nextAccess), at(accessExpiry), row.sessionId());
        jdbc.update("INSERT INTO auth_refresh_token(token_hash,session_id,issued_at,expires_at) VALUES (?,?,?,?)",
            hash(nextRefresh), row.sessionId(), at(now), at(refreshExpiry));
        return RefreshOutcome.success(new Tokens(nextAccess, Duration.between(now, accessExpiry).toSeconds(),
            nextRefresh, Duration.between(now, refreshExpiry).toSeconds()));
    }

    void logout(String refreshToken) {
        transactions.executeWithoutResult(status -> {
            Instant now = databaseNow();
            List<UUID> sessions = jdbc.query("SELECT s.id FROM auth_refresh_token r JOIN auth_session s ON s.id=r.session_id "
                    + "WHERE r.token_hash=? FOR UPDATE OF s",
                (result, row) -> result.getObject(1, UUID.class), hash(refreshToken));
            if (!sessions.isEmpty()) {
                jdbc.update("UPDATE auth_session SET revoked_at=? WHERE id=? AND revoked_at IS NULL", at(now), sessions.get(0));
            }
        });
    }

    AuthenticatedUser authenticateAccess(String accessToken) {
        List<UUID> users = jdbc.query("SELECT user_id FROM auth_session WHERE access_token_hash=? AND revoked_at IS NULL "
                + "AND access_expires_at>clock_timestamp() AND absolute_expires_at>clock_timestamp()",
            (result, row) -> result.getObject(1, UUID.class), hash(accessToken));
        return users.size() == 1 && ownerAllowed(users.get(0)) ? new AuthenticatedUser(users.get(0)) : null;
    }

    private boolean ownerAllowed(UUID userId) {
        return restrictedOwnerUserId == null || restrictedOwnerUserId.equals(userId);
    }

    private static UUID restrictedOwner(boolean enabled, String configuredUserId) {
        if (!enabled) return null;
        UUID parsed;
        try {
            parsed = UUID.fromString(configuredUserId);
        } catch (IllegalArgumentException exception) {
            throw invalidRestrictedOwner();
        }
        if (!parsed.toString().equalsIgnoreCase(configuredUserId)) throw invalidRestrictedOwner();
        return parsed;
    }

    private static IllegalStateException invalidRestrictedOwner() {
        return new IllegalStateException(
            "STOG_AUTH_RESTRICTED_OWNER_USER_ID must be a canonical UUID when restricted-owner mode is enabled");
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    private static Instant providerCredentialExpiry(Instant now, long expiresIn) {
        try {
            Instant expiry = now.plusSeconds(expiresIn);
            if (expiresIn <= 0 || expiry.isAfter(POSTGRES_TIMESTAMP_MAX)) throw AuthException.unavailable();
            at(expiry);
            return expiry;
        } catch (DateTimeException | ArithmeticException exception) {
            throw AuthException.unavailable();
        }
    }

    private static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    private static Instant nullableInstant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static Instant min(Instant first, Instant second) { return first.isBefore(second) ? first : second; }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String username(String raw) {
        if (raw == null) throw AuthException.invalid();
        String value = trimUnicode(raw).toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._-]{2,30}[a-z0-9]")
                || RESERVED_USERNAMES.contains(value)) {
            throw AuthException.invalid();
        }
        return value;
    }

    String currentDisplayName(UUID userId) {
        return jdbc.queryForObject("SELECT display_name FROM stog_user WHERE id=?", String.class, userId);
    }

    static String displayName(String raw) {
        if (raw == null) throw AuthException.invalid();
        String value = trimUnicode(Normalizer.normalize(raw, Normalizer.Form.NFC));
        long codePoints = value.codePoints().count();
        if (codePoints < 1 || codePoints > 40 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw AuthException.invalid();
        }
        return value;
    }

    static String password(String raw) {
        if (raw == null) throw AuthException.invalid();
        String value = Normalizer.normalize(raw, Normalizer.Form.NFC);
        if (value.codePoints().anyMatch(point -> point == 0)) throw AuthException.invalid();
        long codePoints = value.codePoints().count();
        if (codePoints < 8 || codePoints > 128
                || value.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw AuthException.passwordNotAcceptable();
        }
        return value;
    }

    private static String trimUnicode(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int point = value.codePointAt(start);
            if (!unicodeWhitespace(point)) break;
            start += Character.charCount(point);
        }
        while (end > start) {
            int point = value.codePointBefore(end);
            if (!unicodeWhitespace(point)) break;
            end -= Character.charCount(point);
        }
        return value.substring(start, end);
    }

    private static boolean unicodeWhitespace(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }

    static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record Tokens(String accessToken, long accessExpiresIn, String refreshToken, long refreshExpiresIn) {}
    private record PasswordCredential(UUID userId, String passwordHash) {}
    private record LoginOutcome(boolean success, Tokens tokens) {
        static LoginOutcome success(Tokens tokens) { return new LoginOutcome(true, tokens); }
        static LoginOutcome failure() { return new LoginOutcome(false, null); }
    }
    private record RefreshRow(UUID sessionId, Instant expiresAt, Instant usedAt, Instant absoluteExpiresAt, Instant revokedAt,
        UUID userId) {}
    private record RefreshOutcome(boolean success, Tokens tokens) {
        static RefreshOutcome success(Tokens tokens) { return new RefreshOutcome(true, tokens); }
        static RefreshOutcome failure() { return new RefreshOutcome(false, null); }
    }
}
