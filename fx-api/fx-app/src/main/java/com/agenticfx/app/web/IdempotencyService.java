package com.agenticfx.app.web;

import com.agenticfx.app.persistence.JsonCodec;
import com.agenticfx.core.shared.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Idempotency-Key handling for financial operations (approve, reject, create, activate).
 * The first request with a key claims it, runs, and stores its response; a retry with the same key and body
 * gets the stored response back (header {@code Idempotent-Replay: true}); the same key with a different body
 * is rejected; a retry while the first is still running gets 409 so it can back off.
 */
@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final JsonCodec json;

    public IdempotencyService(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper mapper, JsonCodec json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.json = json;
    }

    public ResponseEntity<?> execute(UUID userId, String key, String operation, Object request,
                                     Supplier<ResponseEntity<?>> work) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key header is required (max 128 characters)");
        }
        String hash = sha256(operation + "|" + write(request));
        Boolean claimed = tx.execute(status -> jdbc.sql("""
                        INSERT INTO idempotency_keys (user_id, idempotency_key, operation, request_hash, expires_at)
                        VALUES (:userId, :key, :operation, :hash, :expires)
                        ON CONFLICT (user_id, idempotency_key) DO NOTHING""")
                .param("userId", userId).param("key", key).param("operation", operation).param("hash", hash)
                .param("expires", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1))
                .update() == 1);

        if (Boolean.TRUE.equals(claimed)) {
            ResponseEntity<?> response;
            try {
                response = work.get();
            } catch (RuntimeException e) {
                release(userId, key);
                throw e;
            }
            jdbc.sql("""
                            UPDATE idempotency_keys SET response_status = :status, response_body = CAST(:body AS jsonb)
                            WHERE user_id = :userId AND idempotency_key = :key""")
                    .param("status", response.getStatusCode().value()).param("body", write(response.getBody()))
                    .param("userId", userId).param("key", key)
                    .update();
            return response;
        }

        record Stored(String operation, String hash, Integer status, String body) {
        }
        Stored stored = jdbc.sql("""
                        SELECT operation, request_hash, response_status, response_body::text AS body
                        FROM idempotency_keys WHERE user_id = :userId AND idempotency_key = :key""")
                .param("userId", userId).param("key", key)
                .query((rs, i) -> new Stored(rs.getString("operation"), rs.getString("request_hash"),
                        (Integer) rs.getObject("response_status"), rs.getString("body")))
                .single();
        if (!stored.operation().equals(operation) || !stored.hash().equals(hash)) {
            throw new ConflictException("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was used for a different request");
        }
        if (stored.status() == null) {
            throw new ConflictException("REQUEST_IN_PROGRESS", "A request with this Idempotency-Key is in progress");
        }
        return ResponseEntity.status(stored.status()).header("Idempotent-Replay", "true")
                .body(stored.body() == null ? null : json.tree(stored.body()));
    }

    /** A failed first attempt must not block a corrected retry with the same key. */
    private void release(UUID userId, String key) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = :userId AND idempotency_key = :key AND response_status IS NULL")
                .param("userId", userId).param("key", key).update();
    }

    private String write(Object value) {
        try {
            return value == null ? "null" : mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
