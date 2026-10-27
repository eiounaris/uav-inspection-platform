package com.uav.inspection.messaging;

import com.uav.inspection.messaging.persistence.MessageRejectionMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A successful return through the Spring proxy means rejection metadata has committed. */
@Service
@Profile("database")
public class MessageRejectionRecorder {
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final MessageRejectionMapper mapper;

    public MessageRejectionRecorder(MessageRejectionMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String consumer, byte[] body, String reason, String suppliedMessageId) {
        if (!Set.of("FILE_FINALIZE", "EVIDENCE_ANCHOR").contains(consumer)
                || !Set.of("INVALID_ENVELOPE", "UNKNOWN_EVENT", "MESSAGE_ID_MISMATCH").contains(reason)) {
            throw new IllegalArgumentException("Invalid rejection metadata");
        }
        Objects.requireNonNull(body, "Missing message body");
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
        mapper.insertFirst(UUID.randomUUID().toString(), consumer, hash, body.length, reason,
                canonicalMessageId(suppliedMessageId));
    }

    static String canonicalMessageId(String value) {
        return value != null && value.matches(UUID_PATTERN) ? value : null;
    }
}
