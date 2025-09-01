package space.blockera.twofa.storage;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public class UserRepository {
    private final DataSource ds;
    private final Logger log;

    public UserRepository(DataSource ds, Logger log) { this.ds = ds; this.log = log; }

    public void initSchema() {
        String users = "CREATE TABLE IF NOT EXISTS twofa_users (" +
                "uuid BINARY(16) PRIMARY KEY, " +
                "enabled TINYINT(1) NOT NULL DEFAULT 0, " +
                "secret VARBINARY(512) NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")";
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(users);
        } catch (SQLException e) {
            log.severe("Не удалось инициализировать схему twofa_users: " + e.getMessage());
        }
    }

    public boolean isEnabled(UUID uuid) {
        String sql = "SELECT enabled FROM twofa_users WHERE uuid=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBytes(1, uuidToBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getBoolean(1);
                return false;
            }
        } catch (SQLException e) {
            log.warning("isEnabled: " + e.getMessage());
            return false;
        }
    }

    public Optional<byte[]> getSecret(UUID uuid) {
        String sql = "SELECT secret FROM twofa_users WHERE uuid=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBytes(1, uuidToBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getBytes(1));
                return Optional.empty();
            }
        } catch (SQLException e) {
            log.warning("getSecret: " + e.getMessage());
            return Optional.empty();
        }
    }

    public void upsertSecret(UUID uuid, byte[] secretBytes, boolean enabled) {
        String sql = "INSERT INTO twofa_users(uuid, secret, enabled) VALUES(?,?,?) " +
                "ON DUPLICATE KEY UPDATE secret=VALUES(secret), enabled=VALUES(enabled)";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBytes(1, uuidToBytes(uuid));
            ps.setBytes(2, secretBytes);
            ps.setBoolean(3, enabled);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("upsertSecret: " + e.getMessage());
        }
    }

    public void setEnabled(UUID uuid, boolean enabled) {
        String sql = "UPDATE twofa_users SET enabled=? WHERE uuid=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setBytes(2, uuidToBytes(uuid));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("setEnabled: " + e.getMessage());
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
}