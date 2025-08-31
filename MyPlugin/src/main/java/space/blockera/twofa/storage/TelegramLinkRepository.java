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

public class TelegramLinkRepository {

    public record TgLink(UUID playerUuid, long telegramId, String telegramUsername, Instant linkedAt) {}

    private final HikariDataSource ds;
    private final Logger log;

    public TelegramLinkRepository(HikariDataSource ds, Logger log) {
        this.ds = ds;
        this.log = log;
        initSchema();
    }

    private void initSchema() {
        String sql = """
            CREATE TABLE IF NOT EXISTS telegram_links (
                player_uuid CHAR(36) PRIMARY KEY,
                telegram_id BIGINT NOT NULL,
                telegram_username VARCHAR(64),
                linked_at TIMESTAMP NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (Exception e) {
            log.severe("telegram_links schema init failed: " + e.getMessage());
        }
    }

    public Optional<TgLink> findByPlayer(UUID uuid) {
        String sql = "SELECT telegram_id, telegram_username, linked_at FROM telegram_links WHERE player_uuid = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long tid = rs.getLong("telegram_id");
                    String uname = rs.getString("telegram_username");
                    Instant at = rs.getTimestamp("linked_at").toInstant();
                    return Optional.of(new TgLink(uuid, tid, uname, at));
                }
            }
        } catch (Exception e) {
            log.warning("findByPlayer failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    public void upsert(UUID uuid, long telegramId, String username) {
        String sql = """
            INSERT INTO telegram_links (player_uuid, telegram_id, telegram_username, linked_at)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE telegram_id = VALUES(telegram_id),
                                    telegram_username = VALUES(telegram_username),
                                    linked_at = VALUES(linked_at)
        """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, telegramId);
            ps.setString(3, username);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warning("upsert telegram link failed: " + e.getMessage());
        }
    }

    public void deleteByPlayer(UUID uuid) {
        String sql = "DELETE FROM telegram_links WHERE player_uuid = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warning("deleteByPlayer failed: " + e.getMessage());
        }
    }
}
