package space.blockera.twofa.session;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SessionService {
    private static final CooldownPolicy.CooldownRule ALWAYS_REQUIRE = CooldownPolicy.CooldownRule.always();

    private final Map<UUID, Instant> verifiedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, PendingState> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Instant> globalCooldown = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ConcurrentMap<String, Instant>> perIpCooldown = new ConcurrentHashMap<>();

    private int expireMinutes;
    private CooldownPolicy cooldownPolicy = CooldownPolicy.disabled();

    public SessionService(int expireMinutes) {
        this.expireMinutes = expireMinutes;
    }

    public SessionService(FileConfiguration config) {
        this(config.getInt("session.expire_minutes", 120));
        applyConfig(config);
    }

    public void applyConfig(FileConfiguration config) {
        this.expireMinutes = config.getInt("session.expire_minutes", 120);
        this.cooldownPolicy = CooldownPolicy.fromConfig(config);
    }

    public void markPending(UUID uuid) {
        pending.put(uuid, new PendingState(null, null));
    }

    public void markPending(UUID uuid, CooldownPolicy.CooldownRule rule, String ip) {
        pending.put(uuid, new PendingState(rule, normalizeIp(ip)));
    }

    public void clearPending(UUID uuid) {
        pending.remove(uuid);
    }

    public boolean isPending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    public void markVerified(UUID uuid, String ip) {
        PendingState state = pending.get(uuid);
        markTrusted(uuid);
        CooldownPolicy.CooldownRule rule = state != null && state.rule() != null ? state.rule() : cooldownPolicy.defaultRule();
        String resolvedIp = state != null && state.ip() != null ? state.ip() : normalizeIp(ip);
        recordCooldown(uuid, resolvedIp, rule);
    }

    public void markTrusted(UUID uuid) {
        verifiedUntil.put(uuid, Instant.now().plusSeconds(expireMinutes * 60L));
        clearPending(uuid);
    }

    public boolean isVerified(UUID uuid) {
        Instant until = verifiedUntil.get(uuid);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            verifiedUntil.remove(uuid);
            return false;
        }
        return true;
    }

    public boolean isWithinCooldown(UUID uuid, String ip, CooldownPolicy.CooldownRule rule) {
        CooldownPolicy.CooldownRule effective = rule != null ? rule : cooldownPolicy.defaultRule();
        if (effective == null || effective.alwaysRequire()) {
            return false;
        }
        String normalized = normalizeIp(ip);
        Instant now = Instant.now();
        if (effective.usePerIp() && normalized != null) {
            ConcurrentMap<String, Instant> playerMap = perIpCooldown.get(uuid);
            if (playerMap == null) return false;
            Instant until = playerMap.get(normalized);
            if (until == null) return false;
            if (now.isAfter(until)) {
                playerMap.remove(normalized);
                return false;
            }
            return true;
        }
        Instant until = globalCooldown.get(uuid);
        if (until == null) return false;
        if (now.isAfter(until)) {
            globalCooldown.remove(uuid);
            return false;
        }
        return true;
    }

    public CooldownPolicy.CooldownRule resolveRule(Player player) {
        if (player == null) {
            return cooldownPolicy.defaultRule();
        }
        return cooldownPolicy.resolve(player);
    }

    public CooldownPolicy getCooldownPolicy() {
        return cooldownPolicy;
    }

    public void clear(UUID uuid) {
        verifiedUntil.remove(uuid);
        pending.remove(uuid);
    }

    public static String currentIp(Player player) {
        if (player == null) return null;
        InetSocketAddress address = player.getAddress();
        if (address == null) return null;
        if (address.getAddress() == null) return null;
        return address.getAddress().getHostAddress();
    }

    private void recordCooldown(UUID uuid, String ip, CooldownPolicy.CooldownRule rule) {
        CooldownPolicy.CooldownRule effective = rule != null ? rule : ALWAYS_REQUIRE;
        if (effective.alwaysRequire()) {
            globalCooldown.remove(uuid);
            perIpCooldown.remove(uuid);
            return;
        }

        Instant until = Instant.now().plusSeconds(effective.minutes() * 60L);
        if (effective.usePerIp() && ip != null) {
            perIpCooldown.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>()).put(ip, until);
        } else {
            globalCooldown.put(uuid, until);
        }
    }

    private static String normalizeIp(String ip) {
        if (ip == null) return null;
        String trimmed = ip.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record PendingState(CooldownPolicy.CooldownRule rule, String ip) { }

    public static final class CooldownPolicy {
        private final boolean enabled;
        private final List<CooldownRule> rules;
        private final CooldownRule defaultRule;

        private CooldownPolicy(boolean enabled, List<CooldownRule> rules, CooldownRule defaultRule) {
            this.enabled = enabled;
            this.rules = rules;
            this.defaultRule = defaultRule;
        }

        public static CooldownPolicy fromConfig(FileConfiguration config) {
            ConfigurationSection section = config.getConfigurationSection("policy.cooldown");
            if (section == null) {
                return disabled();
            }

            boolean enabled = section.getBoolean("enabled", true);
            CooldownRule fallback = CooldownRule.fromSection(null, section.getConfigurationSection("default"));
            if (fallback == null) {
                fallback = CooldownRule.remember("default", section.getLong("default_minutes", 1440L), section.getBoolean("default_per_ip", true));
            }

            List<Map<?, ?>> rawRules = section.getMapList("groups");
            if (rawRules == null) {
                rawRules = Collections.emptyList();
            }
            List<CooldownRule> parsed = new java.util.ArrayList<>();
            for (Map<?, ?> map : rawRules) {
                CooldownRule rule = CooldownRule.fromMap(map, fallback);
                if (rule != null) {
                    parsed.add(rule);
                }
            }
            return new CooldownPolicy(enabled, Collections.unmodifiableList(parsed), fallback);
        }

        public static CooldownPolicy disabled() {
            return new CooldownPolicy(false, List.of(), CooldownRule.always());
        }

        public CooldownRule resolve(Player player) {
            if (!enabled) {
                return CooldownRule.always();
            }
            for (CooldownRule rule : rules) {
                if (rule.matches(player)) {
                    return rule;
                }
            }
            return defaultRule;
        }

        public CooldownRule defaultRule() {
            return defaultRule;
        }

        public record CooldownRule(String permission, long minutes, boolean perIp, boolean forceAlways) {
            private static CooldownRule fromSection(String permission, ConfigurationSection section) {
                if (section == null) return null;
                long minutes = section.getLong("minutes", 1440L);
                boolean perIp = section.getBoolean("per_ip", true);
                boolean force = section.getBoolean("force", false) || minutes <= 0;
                return new CooldownRule(permission, Math.max(minutes, 0L), perIp, force);
            }

            private static CooldownRule fromMap(Map<?, ?> map, CooldownRule fallback) {
                if (map == null) return null;
                Object permRaw = map.get("permission");
                if (!(permRaw instanceof String perm) || perm.trim().isEmpty()) {
                    return null;
                }
                String permission = perm.trim();
                long minutes = fallback != null ? fallback.minutes : 1440L;
                boolean perIp = fallback != null && fallback.perIp;
                boolean force = fallback != null && fallback.forceAlways;

                Object minutesRaw = map.get("minutes");
                if (minutesRaw instanceof Number num) {
                    minutes = Math.max(num.longValue(), 0L);
                } else if (minutesRaw instanceof String str && !str.isBlank()) {
                    try {
                        minutes = Math.max(Long.parseLong(str.trim()), 0L);
                    } catch (NumberFormatException ignored) {
                    }
                }

                Object perIpRaw = map.get("per_ip");
                if (perIpRaw instanceof Boolean bool) {
                    perIp = bool;
                }

                Object forceRaw = map.get("force");
                if (forceRaw instanceof Boolean bool) {
                    force = bool;
                }

                if (minutes <= 0) {
                    force = true;
                }

                return new CooldownRule(permission, minutes, perIp, force);
            }

            public static CooldownRule remember(String permission, long minutes, boolean perIp) {
                return new CooldownRule(permission, Math.max(minutes, 0L), perIp, minutes <= 0);
            }

            public static CooldownRule always() {
                return new CooldownRule(null, 0L, false, true);
            }

            public boolean matches(Player player) {
                if (permission == null || permission.isBlank()) return false;
                return player != null && player.hasPermission(permission);
            }

            public boolean alwaysRequire() {
                return forceAlways;
            }

            public boolean usePerIp() {
                return perIp;
            }

            public long minutes() {
                return minutes;
            }

            public String permission() {
                return permission;
            }
        }
    }
}
