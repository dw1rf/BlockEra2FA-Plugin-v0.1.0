package space.blockera.twofa.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

public class DataSourceFactory {
    public static HikariDataSource fromConfig(FileConfiguration cfg) {
        HikariConfig hc = new HikariConfig();
        String host = cfg.getString("storage.host", "127.0.0.1");
        int port = cfg.getInt("storage.port", 3306);
        String db = cfg.getString("storage.database", "security");
        String user = cfg.getString("storage.user", "twofa");
        String pass = cfg.getString("storage.password", "");

        String jdbc = String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=UTC", host, port, db);
        hc.setJdbcUrl(jdbc);
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setMaximumPoolSize(cfg.getInt("storage.pool.maximumPoolSize", 10));
        hc.setPoolName("BlockEraTwoFA-Hikari");
        return new HikariDataSource(hc);
    }
}