package space.blockera.twofa;

import space.blockera.twofa.TwoFAMode;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;

import space.blockera.twofa.commands.TwoFACommand;
import space.blockera.twofa.i18n.Messages;
import space.blockera.twofa.listeners.SecurityListeners;
import space.blockera.twofa.listeners.SecurityFreezeListener;
import space.blockera.twofa.security.CryptoUtil;
import space.blockera.twofa.session.SessionService;
import space.blockera.twofa.session.TrustedDeviceService;
import space.blockera.twofa.storage.ChallengeRepository;
import space.blockera.twofa.storage.DataSourceFactory;
import space.blockera.twofa.storage.TelegramLinkRepository;
import space.blockera.twofa.storage.TelegramSessionRepository;
import space.blockera.twofa.storage.UserRepository;
import space.blockera.twofa.storage.TrustedDeviceRepository;
import space.blockera.twofa.totp.TotpService;

// онлайн
import space.blockera.twofa.storage.OnlineRepository;
import space.blockera.twofa.listeners.OnlineListeners;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;

public class BlockEraTwoFAPlugin extends JavaPlugin {

    private HikariDataSource dataSource;
    private UserRepository userRepository;
    private SessionService sessionService;
    private TotpService totpService;
    private CryptoUtil crypto;
    private TwoFACommand command;
    private Messages messages;
    private TelegramLinkRepository tgLinks;
    private ChallengeRepository challenges;
    private TelegramSessionRepository telegramSessions;
    private TrustedDeviceRepository trustedDevicesRepository;
    private TwoFAMode mode;
    private SecurityListeners securityListeners;
    private SecurityFreezeListener securityFreezeListener;
<<<<<<< ours
=======
    private TrustedDeviceService trustedDeviceService;
>>>>>>> theirs
    private Metrics metrics;
    private int activeMetricsId = -1;


    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        mergeResourceDefaults("config.yml");
        mergeResourceDefaults("messages.yml");

        reloadCore();

        // команда
        PluginCommand pc = getCommand("2fa");
        if (pc == null) {
            getLogger().severe("Command '2fa' отсутствует в plugin.yml или не попала в JAR");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        pc.setExecutor(command);
        pc.setTabCompleter(command);

        // слушатели безопасности
        this.securityListeners = new SecurityListeners(this, userRepository, sessionService, trustedDeviceService, messages);
        Bukkit.getPluginManager().registerEvents(securityListeners, this);
        this.securityFreezeListener = new SecurityFreezeListener(this, tgLinks, telegramSessions, messages);
        Bukkit.getPluginManager().registerEvents(securityFreezeListener, this);

        // онлайн: апдейт таблицы + обработчик очереди logout
        OnlineRepository onlineRepo = new OnlineRepository(getDataSource());
        Bukkit.getPluginManager().registerEvents(
                new OnlineListeners(this, onlineRepo),
                this
        );

        getLogger().info("BlockEraTwoFA включён.");
    }

    @Override
    public void onDisable() {
        if (dataSource != null) dataSource.close();
        shutdownMetrics();
    }

    /** Переинициализация всего (вызывается из /2fa reload). */
    public void reloadCore() {
        mergeResourceDefaults("config.yml");
        reloadConfig();
        FileConfiguration cfg = getConfig();

        mergeResourceDefaults("messages.yml");

        // messages.yml
        this.messages = new Messages(this);

        if (dataSource != null) dataSource.close();
        this.dataSource = DataSourceFactory.fromConfig(cfg);

        // репозитории
        this.userRepository = new UserRepository(dataSource, getLogger());
        this.userRepository.initSchema();
        this.tgLinks = new TelegramLinkRepository(dataSource, getLogger());
        this.challenges = new ChallengeRepository(dataSource, getLogger());
        this.telegramSessions = new TelegramSessionRepository(dataSource, getLogger());
        this.trustedDevicesRepository = new TrustedDeviceRepository(dataSource, getLogger());
        this.trustedDevicesRepository.initSchema();

        // ключ шифрования: ENV -> config.yml -> PLAINTEXT
        String envVar = cfg.getString("security.secret_encryption_key_env", "TWOFA_MASTER_KEY");
        String b64 = System.getenv(envVar);
        if (b64 == null || b64.isEmpty()) {
            b64 = cfg.getString("security.secret_encryption_key_b64", "");
        }
        byte[] key = null;
        if (b64 != null && !b64.isEmpty()) {
            try {
                key = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Некорректный Base64 ключ, секреты будут PLAINTEXT.");
            }
        } else {
            getLogger().warning("Ключ шифрования не задан — секреты будут PLAINTEXT.");
        }
        this.crypto = new CryptoUtil(key, getLogger());

        // сервисы
        this.totpService = new TotpService(cfg);
        this.sessionService = new SessionService(cfg);
        this.trustedDeviceService = new TrustedDeviceService(trustedDevicesRepository, cfg);

        if (this.command == null) {
            this.command = new TwoFACommand(
                    this,
                    userRepository,
                    totpService,
                    sessionService,
                    crypto,
                    messages,
                    tgLinks,
                    challenges,
                    trustedDeviceService
            );
        } else {
            this.command.rewire(
                    userRepository,
                    totpService,
                    sessionService,
                    crypto,
                    messages,
                    tgLinks,
                    challenges,
                    trustedDeviceService
            );
        }
        this.command.reloadSettings();

        if (this.securityListeners != null) {
            this.securityListeners.rewire(userRepository, sessionService, trustedDeviceService);
            this.securityListeners.setMessages(messages);
            this.securityListeners.reloadSettings();
        }
        if (this.securityFreezeListener != null) {
            this.securityFreezeListener.rewire(tgLinks, telegramSessions);
            this.securityFreezeListener.setMessages(messages);
            this.securityFreezeListener.reloadSettings();
        }

        // создать таблицы онлайна/очереди, если их ещё нет
        initOnlineSchema();

        configureMetrics(cfg);
    }

    // ===== helpers =====

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    /** Создание таблиц tg_online и tg_actions. */
    private void initOnlineSchema() {
        try (var c = dataSource.getConnection();
             var st = c.createStatement()) {

            st.execute("""
                CREATE TABLE IF NOT EXISTS tg_online (
                  uuid CHAR(36) PRIMARY KEY,
                  name VARCHAR(32) NOT NULL,
                  online TINYINT(1) NOT NULL DEFAULT 0,
                  last_seen TIMESTAMP NULL,
                  last_world VARCHAR(64) NULL,
                  last_server VARCHAR(64) NULL,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ON UPDATE CURRENT_TIMESTAMP,
                  INDEX (online), INDEX (last_seen)
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS tg_actions (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  action ENUM('LOGOUT') NOT NULL,
                  player_uuid CHAR(36) NOT NULL,
                  reason VARCHAR(200) NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  processed_at TIMESTAMP NULL,
                  INDEX (processed_at), INDEX (player_uuid)
                )
            """);

        } catch (Exception e) {
            getLogger().warning("Не удалось создать таблицы онлайна: " + e.getMessage());
        }
    }

    private void mergeResourceDefaults(String resourcePath) {
        File file = new File(getDataFolder(), resourcePath);
        if (!file.exists()) {
            saveResource(resourcePath, false);
            return;
        }

        try (InputStream defaultsStream = getResource(resourcePath)) {
            if (defaultsStream == null) {
                return;
            }

            try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
                YamlConfiguration existing = YamlConfiguration.loadConfiguration(file);

                boolean changed = false;
                for (String key : defaults.getKeys(true)) {
                    if (!existing.contains(key)) {
                        existing.set(key, defaults.get(key));
                        changed = true;
                    }
                }

                if (changed) {
                    try {
                        existing.save(file);
                    } catch (IOException ex) {
                        getLogger().log(Level.WARNING, "Не удалось обновить " + resourcePath + " новыми значениями", ex);
                    }
                }
            }
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Не удалось прочитать ресурс " + resourcePath, ex);
        }
    }

    private void configureMetrics(FileConfiguration cfg) {
        boolean enabled = cfg.getBoolean("telemetry.bstats.enabled", true);
        int pluginId = cfg.getInt("telemetry.bstats.plugin_id", -1);

        if (!enabled || pluginId <= 0) {
            shutdownMetrics();
            return;
        }

        if (metrics != null && activeMetricsId != pluginId) {
            shutdownMetrics();
        }

        if (metrics == null) {
            metrics = new Metrics(this, pluginId);
            activeMetricsId = pluginId;
        }
    }

    private void shutdownMetrics() {
        if (metrics == null) {
            return;
        }
        try {
            metrics.shutdown();
        } catch (NoSuchMethodError ignored) {
            // bStats < 3.0.2 не поддерживает shutdown
        }
        metrics = null;
        activeMetricsId = -1;
    }

    // геттеры
    public UserRepository getUserRepository() { return userRepository; }
    public SessionService getSessionService() { return sessionService; }
    public TotpService getTotpService() { return totpService; }
    public CryptoUtil getCrypto() { return crypto; }
    public Messages getMessages() { return messages; }
    public TelegramLinkRepository getTelegramLinks() { return tgLinks; }
    public ChallengeRepository getChallenges() { return challenges; }
    public TelegramSessionRepository getTelegramSessions() { return telegramSessions; }
    public TrustedDeviceService getTrustedDeviceService() { return trustedDeviceService; }
    public TwoFAMode getMode() { return mode; }
}
