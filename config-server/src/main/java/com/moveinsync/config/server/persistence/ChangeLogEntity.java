package com.moveinsync.config.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Append-only transactional change log entity.
 *
 * Guarantees:
 * - Version is the monotonic primary key.
 * - History is strictly immutable; entries are never updated or deleted.
 * - Every state transition is permanently recorded with author, reason, and JSON payload.
 * - Current in-memory state is rebuilt on server startup by replaying these entries.
 */
@Entity
@Table(name = "change_log")
public class ChangeLogEntity {

    @Id
    @Column(name = "version", nullable = false, updatable = false)
    private Long version;

    @Column(name = "author", nullable = false, updatable = false)
    private String author;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private ChangeKind kind;

    @Lob
    @Column(name = "payload_json", nullable = false, updatable = false, length = 1048576)
    private String payloadJson;

    public ChangeLogEntity() {
        // Required by JPA
    }

    public ChangeLogEntity(Long version, String author, String reason, Instant createdAt, ChangeKind kind, String payloadJson) {
        this.version = version;
        this.author = author;
        this.reason = reason;
        this.createdAt = createdAt;
        this.kind = kind;
        this.payloadJson = payloadJson;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public ChangeKind getKind() {
        return kind;
    }

    public void setKind(ChangeKind kind) {
        this.kind = kind;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChangeLogEntity that)) return false;
        return Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version);
    }

    @Override
    public String toString() {
        return "ChangeLogEntity{" +
                "version=" + version +
                ", author='" + author + '\'' +
                ", reason='" + reason + '\'' +
                ", kind=" + kind +
                ", createdAt=" + createdAt +
                '}';
    }
}
