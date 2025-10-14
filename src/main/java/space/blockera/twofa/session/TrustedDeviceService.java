package space.blockera.twofa.session;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import space.blockera.twofa.storage.TrustedDeviceRepository;
import space.blockera.twofa.storage.TrustedDeviceRepository.TrustedDeviceRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class TrustedDeviceService {
    private final TrustedDeviceRepository repository;
    private final FloodgateDetector floodgateDetector;
    private boolean enabled;
    private Duration ttl;

    public TrustedDeviceService(TrustedDeviceRepository repository, FileConfiguration config) {
        this.repository = repository;
        this.floodgateDetector = FloodgateDetector.detect();
        reload(config);
    }

    public void reload(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("security.trusted_devices");
        if (section == null) {
            this.enabled = false;
            this.ttl = Duration.ofDays(30);
            return;
        }
        this.enabled = section.getBoolean("enabled", true);
        long days = Math.max(1L, section.getLong("expire_days", 30L));
        this.ttl = Duration.ofDays(days);
    }

    public boolean isTrusted(Player player) {
        if (!enabled) {
            return false;
        }
        TrustedFingerprint fingerprint = fingerprint(player);
        if (fingerprint == null) {
            return false;
        }
        Optional<TrustedDeviceRecord> record = repository.find(player.getUniqueId(), fingerprint.ip(), fingerprint.locale(), fingerprint.platform());
        if (record.isEmpty()) {
            return false;
        }
        Instant now = Instant.now();
        if (record.get().trustedUntil().isBefore(now)) {
            return false;
        }
        repository.touch(record.get().id());
        return true;
    }

    public void remember(Player player) {
        if (!enabled) {
            return;
        }
        TrustedFingerprint fingerprint = fingerprint(player);
        if (fingerprint == null) {
            return;
        }
        repository.upsert(player.getUniqueId(), fingerprint.ip(), fingerprint.locale(), fingerprint.platform(), Instant.now().plus(ttl));
    }

    public void forget(UUID uuid) {
        repository.deleteAll(uuid);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long ttlDays() {
        return ttl.toDays();
    }

    private TrustedFingerprint fingerprint(Player player) {
        String ip = SessionService.currentIp(player);
        if (ip == null) {
            return null;
        }
        String localeValue = safeLocale(player);
        String platform = detectPlatform(player);
        return new TrustedFingerprint(ip, localeValue, platform);
    }

    private String safeLocale(Player player) {
        try {
            String value = player.getLocale();
            if (value != null && !value.isBlank()) {
                return value.toLowerCase(Locale.ROOT);
            }
        } catch (NoSuchMethodError error) {
            Locale locale = player.locale();
            if (locale != null) {
                return locale.toString().toLowerCase(Locale.ROOT);
            }
        }
        return "unknown";
    }

    private String detectPlatform(Player player) {
        if (floodgateDetector != null && floodgateDetector.isBedrock(player.getUniqueId())) {
            return "bedrock";
        }
        return "java";
    }

    private record TrustedFingerprint(String ip, String locale, String platform) {
        private TrustedFingerprint {
            Objects.requireNonNull(ip, "ip");
            Objects.requireNonNull(locale, "locale");
            Objects.requireNonNull(platform, "platform");
        }
    }

    private static final class FloodgateDetector {
        private final Object api;
        private final java.lang.reflect.Method isFloodgate;

        private FloodgateDetector(Object api, java.lang.reflect.Method isFloodgate) {
            this.api = api;
            this.isFloodgate = isFloodgate;
        }

        static FloodgateDetector detect() {
            try {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                java.lang.reflect.Method instanceMethod = apiClass.getMethod("getInstance");
                Object api = instanceMethod.invoke(null);
                java.lang.reflect.Method checkMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
                return new FloodgateDetector(api, checkMethod);
            } catch (Throwable ignored) {
                return null;
            }
        }

        boolean isBedrock(UUID uuid) {
            try {
                Object result = isFloodgate.invoke(api, uuid);
                return result instanceof Boolean bool && bool;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
