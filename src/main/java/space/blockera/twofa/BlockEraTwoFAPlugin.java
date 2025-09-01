package space.blockera.twofa;

import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import space.blockera.twofa.commands.TwoFACommand;
import space.blockera.twofa.i18n.Messages;
import space.blockera.twofa.listeners.SecurityListeners;
import space.blockera.twofa.listeners.SecurityFreezeListener;
import space.blockera.twofa.security.CryptoUtil;
import space.blockera.twofa.session.SessionService;
import space.blockera.twofa.storage.ChallengeRepository;
import space.blockera.twofa.storage.DataSourceFactory;
import space.blockera.twofa.storage.TelegramLinkRepository;
import space.blockera.twofa.storage.TelegramSessionRepository;
import space.blockera.twofa.storage.UserRepository;
import space.blockera.twofa.totp.TotpService;

import java.util.Base64;

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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // положим messages.yml при первом запуске
        saveResource("messages.yml", false);

        reloadCore();

        // безопасная регистрация команды
        PluginCommand pc = getCommand("2fa");
        if (pc == null) {
            getLogger().severe("Command '2fa' отсутствует в plugin.yml или не попала в JAR");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        pc.setExecutor(command);
        pc.setTabCompleter(command);

        // слушатели
        Bukkit.getPluginManager().registerEvents(
                new SecurityListeners(this, userRepository, sessionService),
                this
        );
        Bukkit.getPluginManager().registerEvents(
                new SecurityFreezeListener(this, tgLinks, telegramSessions),
                this
        );

        getLogger().info("BlockEraTwoFA включён.");
    }

    @Override
    public void onDisable() {
        if (dataSource != null) dataSource.close();
    }

    /** Переинициализация всего (вызывается из /2fa reload). */
    public void reloadCore() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

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
        this.sessionService = new SessionService(cfg.getInt("session.expire_minutes", 120));

        if (this.command == null) {
            this.command = new TwoFACommand(
                    this,
                    userRepository,
                    totpService,
                    sessionService,
                    crypto,
                    messages,
                    tgLinks,
                    challenges
            );
        } else {
            this.command.rewire(
                    userRepository,
                    totpService,
                    sessionService,
                    crypto,
                    messages,
                    tgLinks,
                    challenges
            );
        }
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
}
