package space.blockera.twofa.storage;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public class ChallengeRepository {

    public record Challenge(UUID playerUuid, String token, Instant expiresAt) {}

    private final HikariDataSource ds;
    private final Logger log;

    public ChallengeRepository(HikariDataSource ds, Logger log) {
        this.ds = ds;
        this.log = log;
        initSchema();
    }

    private void initSchema() {
        String sql = """
            CREATE TABLE IF NOT EXISTS tg_challenges (
                token VARCHAR(16) PRIMARY KEY,
                player_uuid CHAR(36) NOT NULL,
                expires_at TIMESTAMP NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (Exception e) {
            log.severe("tg_challenges schema init failed: " + e.getMessage());
        }
    }

    public void create(UUID uuid, String token, Instant expiresAt) {
        String sql = "INSERT INTO tg_challenges (token, player_uuid, expires_at) VALUES (?, ?, ?)";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, uuid.toString());
            ps.setTimestamp(3, Timestamp.from(expiresAt));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warning("create challenge failed: " + e.getMessage());
        }
    }

    public Optional<Challenge> findValidByToken(String token) {
        String sql = "SELECT player_uuid, expires_at FROM tg_challenges WHERE token = ? AND expires_at > NOW()";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UUID u = UUID.fromString(rs.getString("player_uuid"));
                    Instant exp = rs.getTimestamp("expires_at").toInstant();
                    return Optional.of(new Challenge(u, token, exp));
                }
            }
        } catch (Exception e) {
            log.warning("findValidByToken failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    public void delete(String token) {
        String sql = "DELETE FROM tg_challenges WHERE token = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warning("delete challenge failed: " + e.getMessage());
        }
    }
}
