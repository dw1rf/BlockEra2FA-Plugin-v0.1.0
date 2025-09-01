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

    // теперь храним и имя игрока
    public record Challenge(UUID playerUuid, String playerName, String token, Instant expiresAt) {}

    private final HikariDataSource ds;
    private final Logger log;

    public ChallengeRepository(HikariDataSource ds, Logger log) {
        this.ds = ds;
        this.log = log;
        initSchema();
    }

    private void initSchema() {
        // базовая схема с player_name
        String create = """
            CREATE TABLE IF NOT EXISTS tg_challenges (
                token VARCHAR(16) PRIMARY KEY,
                player_uuid CHAR(36) NOT NULL,
                player_name VARCHAR(16) NULL,
                expires_at TIMESTAMP NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(create)) {
            ps.executeUpdate();
        } catch (Exception e) {
            log.severe("tg_challenges create failed: " + e.getMessage());
        }

        // на старых БД могло не быть колонки player_name — добавим, если отсутствует
        try (Connection c = ds.getConnection()) {
            // проверим через INFORMATION_SCHEMA
            boolean has;
            try (PreparedStatement chk = c.prepareStatement(
                    "SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tg_challenges' AND COLUMN_NAME = 'player_name'")) {
                try (ResultSet rs = chk.executeQuery()) {
                    has = rs.next();
                }
            }
            if (!has) {
                try (PreparedStatement alter = c.prepareStatement(
                        "ALTER TABLE tg_challenges ADD COLUMN player_name VARCHAR(16) NULL AFTER player_uuid")) {
                    alter.executeUpdate();
                    log.info("tg_challenges: added missing column player_name");
                }
            }
        } catch (Exception e) {
            // если падает — не критично, просто логируем
            log.warning("tg_challenges migrate(player_name) warn: " + e.getMessage());
        }
    }

    /** Новая версия: с именем игрока. */
    public void create(UUID uuid, String playerName, String token, Instant expiresAt) {
        String sql = "INSERT INTO tg_challenges (token, player_uuid, player_name, expires_at) VALUES (?, ?, ?, ?)";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, uuid.toString());
            ps.setString(3, playerName);
            ps.setTimestamp(4, Timestamp.from(expiresAt));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warning("create challenge failed: " + e.getMessage());
        }
    }

    /** Старая сигнатура оставлена для совместимости — проксирует с null именем. */
    public void create(UUID uuid, String token, Instant expiresAt) {
        create(uuid, null, token, expiresAt);
    }

    public Optional<Challenge> findValidByToken(String token) {
        String sql = "SELECT player_uuid, player_name, expires_at " +
                     "FROM tg_challenges WHERE token = ? AND expires_at > NOW()";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UUID u = UUID.fromString(rs.getString("player_uuid"));
                    String name = rs.getString("player_name"); // может быть null для старых записей
                    Instant exp = rs.getTimestamp("expires_at").toInstant();
                    return Optional.of(new Challenge(u, name, token, exp));
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
