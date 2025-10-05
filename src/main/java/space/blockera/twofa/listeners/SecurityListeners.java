package space.blockera.twofa.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import space.blockera.twofa.session.SessionService;
import space.blockera.twofa.storage.UserRepository;
import space.blockera.twofa.i18n.Messages;

import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

public class SecurityListeners implements Listener {
    private final Plugin plugin;
    private UserRepository repo;
    private SessionService sessions;
    private Messages messages;
    private String requiredPerm;
    private Set<String> allowedWhenPending;
    private float freezeWalkSpeed;
    private float freezeFlySpeed;
    private boolean freezeInvulnerable;
    private boolean freezeCollidable;
    private float unlockWalkSpeed;
    private float unlockFlySpeed;
    private boolean unlockInvulnerable;
    private boolean unlockCollidable;
    private String confirmPlaceholder;

    public SecurityListeners(Plugin plugin, UserRepository repo, SessionService sessions, Messages messages) {
        this.plugin = plugin;
        this.repo = repo;
        this.sessions = sessions;
        this.messages = messages;
        reloadSettings();
    }

    public void setMessages(Messages messages) { this.messages = messages; }

    public void rewire(UserRepository repo, SessionService sessions) {
        this.repo = repo;
        this.sessions = sessions;
    }

    public void reloadSettings() {
        this.requiredPerm = plugin.getConfig().getString("policy.required_permission", "blockera.twofa.required");
        this.allowedWhenPending = new HashSet<>();
        for (String value : plugin.getConfig().getStringList("ui.allow_commands_when_pending")) {
            if (value == null) continue;
            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) continue;
            allowedWhenPending.add(trimmed);
            if (trimmed.startsWith("/")) {
                allowedWhenPending.add(trimmed.substring(1));
            }
        }
        this.freezeWalkSpeed = (float) plugin.getConfig().getDouble("ui.freeze.walk_speed", 0.0);
        this.freezeFlySpeed = (float) plugin.getConfig().getDouble("ui.freeze.fly_speed", 0.0);
        this.freezeInvulnerable = plugin.getConfig().getBoolean("ui.freeze.invulnerable", true);
        this.freezeCollidable = plugin.getConfig().getBoolean("ui.freeze.collidable", false);
        this.unlockWalkSpeed = (float) plugin.getConfig().getDouble("ui.unlock.walk_speed", 0.2);
        this.unlockFlySpeed = (float) plugin.getConfig().getDouble("ui.unlock.fly_speed", 0.1);
        this.unlockInvulnerable = plugin.getConfig().getBoolean("ui.unlock.invulnerable", false);
        this.unlockCollidable = plugin.getConfig().getBoolean("ui.unlock.collidable", true);

        String confirmAlias = "confirm";
        var confirmList = plugin.getConfig().getStringList("commands.confirm");
        for (String alias : confirmList) {
            if (alias != null && !alias.trim().isEmpty()) {
                confirmAlias = alias.trim();
                break;
            }
        }
        this.confirmPlaceholder = "/2fa " + confirmAlias + " <код>";
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        boolean must = p.hasPermission(requiredPerm);
        boolean enabled = repo.isEnabled(u);
        if (!must || !enabled) {
            return;
        }

        var rule = sessions.resolveRule(p);
        String ip = SessionService.currentIp(p);
        if (sessions.isWithinCooldown(u, ip, rule)) {
            sessions.markTrusted(u);
            return;
        }

        if (!sessions.isVerified(u)) {
            sessions.markPending(u, rule, ip);
            freeze(p);
            messages.send(p, "pending.prompt", Map.of("confirm", confirmPlaceholder));
        }
    }

    private boolean isLocked(Player p) {
        UUID u = p.getUniqueId();
        return sessions.isPending(u) && !sessions.isVerified(u);
    }

    private void freeze(Player p) {
        p.setWalkSpeed(freezeWalkSpeed);
        p.setFlySpeed(freezeFlySpeed);
        p.setInvulnerable(freezeInvulnerable);
        p.setCollidable(freezeCollidable);
    }

    private void unfreeze(Player p) {
        p.setWalkSpeed(unlockWalkSpeed);
        p.setFlySpeed(unlockFlySpeed);
        p.setInvulnerable(unlockInvulnerable);
        p.setCollidable(unlockCollidable);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { sessions.clear(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!isLocked(p)) return;
        if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ()) {
            e.setTo(e.getFrom());
        }
    }

    @EventHandler
    public void onCmd(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!isLocked(p)) return;
        String msg = e.getMessage();
        String base = msg.contains(" ") ? msg.substring(0, msg.indexOf(' ')) : msg;
        String normalized = base.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (allowedWhenPending.contains(normalized) || allowedWhenPending.contains("/" + normalized)) return;
        e.setCancelled(true);
        messages.send(p, "blocked.command", Map.of("confirm", confirmPlaceholder));
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!isLocked(p)) return;
        e.setCancelled(true);
        messages.send(p, "blocked.chat", Map.of("confirm", confirmPlaceholder));
    }

    @EventHandler
    public void onLegacyChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!isLocked(p)) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isLocked(p)) return;
        e.setCancelled(true);
    }

    public void onVerified(Player p) { unfreeze(p); }
}