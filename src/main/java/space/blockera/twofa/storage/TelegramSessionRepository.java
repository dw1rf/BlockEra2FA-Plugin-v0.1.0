package space.blockera.twofa.storage;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public class TelegramSessionRepository {
    private final HikariDataSource ds;
    private final Logger log;

    public TelegramSessionRepository(HikariDataSource ds, Logger log) {
        this.ds = ds;
        this.log = log;
        init();
    }

    private void init() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS tg_sessions (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              player_uuid CHAR(36) NOT NULL,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              expires_at TIMESTAMP NOT NULL,
              status ENUM('PENDING','APPROVED','DENIED') NOT NULL DEFAULT 'PENDING',
              approved_at TIMESTAMP NULL,
              ip VARCHAR(45) NULL,
              INDEX idx_uuid_status (player_uuid, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(ddl)) {
            ps.executeUpdate();
        } catch (Exception e) {
            log.severe("tg_sessions ddl failed: " + e.getMessage());
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='telegram_links' AND COLUMN_NAME='last_verified_at'"
             )) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    try (PreparedStatement alter = c.prepareStatement(
                        "ALTER TABLE telegram_links ADD COLUMN last_verified_at TIMESTAMP NULL")) {
                        alter.executeUpdate();
                    }
                }
            }
        } catch (Exception e) {
            log.warning("telegram_links migrate last_verified_at: " + e.getMessage());
        }
    }

    public long createPending(UUID uuid, Instant expiresAt, String ip) {
        String sql = "INSERT INTO tg_sessions (player_uuid, expires_at, ip) VALUES (?, ?, ?)";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString());
            ps.setTimestamp(2, Timestamp.from(expiresAt));
            ps.setString(3, ip);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (Exception e) {
            log.warning("createPending failed: " + e.getMessage());
        }
        return -1L;
    }

    public Optional<String> getStatus(UUID uuid) {
        String sql = "SELECT status FROM tg_sessions WHERE player_uuid=? ORDER BY id DESC LIMIT 1";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getString(1));
            }
        } catch (Exception e) {
            log.warning("getStatus failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<Boolean> isApproved(UUID uuid) {
        String sql = "SELECT status FROM tg_sessions WHERE player_uuid=? ORDER BY id DESC LIMIT 1";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String s = rs.getString(1);
                    if ("APPROVED".equals(s)) return Optional.of(Boolean.TRUE);
                    if ("DENIED".equals(s))   return Optional.of(Boolean.FALSE);
                    return Optional.empty(); // PENDING
                }
            }
        } catch (Exception e) {
            log.warning("isApproved failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    public void markApproved(UUID uuid) {
        String sql = "UPDATE tg_sessions SET status='APPROVED', approved_at=NOW() WHERE player_uuid=? AND status='PENDING' ORDER BY id DESC LIMIT 1";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warning("markApproved failed: " + e.getMessage());
        }
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE telegram_links SET last_verified_at=NOW() WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warning("update last_verified_at failed: " + e.getMessage());
        }
    }

    public void markDenied(UUID uuid) {
        String sql = "UPDATE tg_sessions SET status='DENIED' WHERE player_uuid=? AND status='PENDING' ORDER BY id DESC LIMIT 1";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warning("markDenied failed: " + e.getMessage());
        }
    }

    public boolean isCooldownOk(UUID uuid, long minutes) {
        if (minutes <= 0) return false;
        String sql = "SELECT last_verified_at FROM telegram_links WHERE player_uuid=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getTimestamp(1) != null) {
                    Instant ts = rs.getTimestamp(1).toInstant();
                    return ts.plusSeconds(minutes * 60).isAfter(Instant.now());
                }
            }
        } catch (Exception e) {
            log.warning("isCooldownOk failed: " + e.getMessage());
        }
        return false;
    }
}
