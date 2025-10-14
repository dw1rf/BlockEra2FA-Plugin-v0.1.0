package space.blockera.twofa.storage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public class TrustedDeviceRepository {
    private final DataSource dataSource;
    private final Logger log;

    public TrustedDeviceRepository(DataSource dataSource, Logger log) {
        this.dataSource = dataSource;
        this.log = log;
    }

    public void initSchema() {
        String sql = """
                CREATE TABLE IF NOT EXISTS twofa_trusted_devices (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  uuid BINARY(16) NOT NULL,
                  ip VARCHAR(45) NOT NULL,
                  locale VARCHAR(32) NOT NULL,
                  platform VARCHAR(16) NOT NULL,
                  trusted_until TIMESTAMP NOT NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  last_used TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uniq_device (uuid, ip, locale, platform)
                )""";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException ex) {
            log.severe("Не удалось инициализировать таблицу twofa_trusted_devices: " + ex.getMessage());
        }
    }

    public Optional<TrustedDeviceRecord> find(UUID uuid, String ip, String locale, String platform) {
        String sql = "SELECT id, trusted_until FROM twofa_trusted_devices WHERE uuid=? AND ip=? AND locale=? AND platform=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setBytes(1, uuidToBytes(uuid));
            ps.setString(2, ip);
            ps.setString(3, locale);
            ps.setString(4, platform);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long id = rs.getLong("id");
                Timestamp ts = rs.getTimestamp("trusted_until");
                Instant until = ts != null ? ts.toInstant() : Instant.EPOCH;
                return Optional.of(new TrustedDeviceRecord(id, until));
            }
        } catch (SQLException ex) {
            log.warning("find trusted device: " + ex.getMessage());
            return Optional.empty();
        }
    }

    public void upsert(UUID uuid, String ip, String locale, String platform, Instant trustedUntil) {
        String sql = """
                INSERT INTO twofa_trusted_devices(uuid, ip, locale, platform, trusted_until)
                VALUES(?,?,?,?,?)
                ON DUPLICATE KEY UPDATE trusted_until=VALUES(trusted_until)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setBytes(1, uuidToBytes(uuid));
            ps.setString(2, ip);
            ps.setString(3, locale);
            ps.setString(4, platform);
            ps.setTimestamp(5, Timestamp.from(trustedUntil));
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.warning("upsert trusted device: " + ex.getMessage());
        }
    }

    public void touch(long id) {
        String sql = "UPDATE twofa_trusted_devices SET last_used=CURRENT_TIMESTAMP WHERE id=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.warning("touch trusted device: " + ex.getMessage());
        }
    }

    public void deleteAll(UUID uuid) {
        String sql = "DELETE FROM twofa_trusted_devices WHERE uuid=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setBytes(1, uuidToBytes(uuid));
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.warning("delete trusted devices: " + ex.getMessage());
        }
    }

    private static byte[] uuidToBytes(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] buffer = new byte[16];
        for (int i = 0; i < 8; i++) buffer[i] = (byte) (msb >>> (8 * (7 - i)));
        for (int i = 0; i < 8; i++) buffer[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        return buffer;
    }

    public record TrustedDeviceRecord(long id, Instant trustedUntil) { }
}
