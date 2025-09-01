// src/main/java/space/blockera/twofa/storage/OnlineRepository.java
package space.blockera.twofa.storage;

import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;

public class OnlineRepository {
    private final DataSource ds;
    public OnlineRepository(DataSource ds) { this.ds = ds; }

    public void upsertOnline(UUID uuid, String name, boolean online, String world, String server) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO tg_online (uuid,name,online,last_seen,last_world,last_server) " +
                 "VALUES (?,?,?,?,?,?) " +
                 "ON DUPLICATE KEY UPDATE name=VALUES(name), online=VALUES(online), last_seen=VALUES(last_seen), " +
                 "last_world=VALUES(last_world), last_server=VALUES(last_server)")) {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setInt(3, online ? 1 : 0);
            ps.setTimestamp(4, now);
            ps.setString(5, world);
            ps.setString(6, server);
            ps.executeUpdate();
        }
    }

    public void markOffline(UUID uuid) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE tg_online SET online=0, last_seen=NOW() WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    public ResultSet fetchPendingLogout(Connection c) throws SQLException {
        PreparedStatement ps = c.prepareStatement(
            "SELECT id, player_uuid, reason FROM tg_actions " +
            "WHERE action='LOGOUT' AND processed_at IS NULL ORDER BY id ASC LIMIT 50",
            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
        return ps.executeQuery();
    }

    public void markProcessed(long id) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE tg_actions SET processed_at=NOW() WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }
}
