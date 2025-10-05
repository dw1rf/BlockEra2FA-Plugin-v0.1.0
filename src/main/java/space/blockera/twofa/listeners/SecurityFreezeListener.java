package space.blockera.twofa.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import space.blockera.twofa.storage.TelegramLinkRepository;
import space.blockera.twofa.storage.TelegramSessionRepository;
import space.blockera.twofa.i18n.Messages;

import java.time.Instant;
import java.util.*;

public class SecurityFreezeListener implements Listener {
    private final Plugin plugin;
    private TelegramLinkRepository links;
    private TelegramSessionRepository sessions;
    private final Set<UUID> frozen = new HashSet<>();
    private Messages messages;
    private float freezeWalkSpeed;
    private float freezeFlySpeed;
    private boolean freezeInvulnerable;
    private boolean freezeCollidable;
    private float unlockWalkSpeed;
    private float unlockFlySpeed;
    private boolean unlockInvulnerable;
    private boolean unlockCollidable;
    private String kickPendingMessage;
    private String kickDeniedMessage;

    public SecurityFreezeListener(Plugin plugin, TelegramLinkRepository links, TelegramSessionRepository sessions, Messages messages) {
        this.plugin = plugin;
        this.links = links;
        this.sessions = sessions;
        this.messages = messages;
        reloadSettings();
    }

    public void setMessages(Messages messages) { this.messages = messages; }

    public void rewire(TelegramLinkRepository links, TelegramSessionRepository sessions) {
        this.links = links;
        this.sessions = sessions;
    }

    public void reloadSettings() {
        this.freezeWalkSpeed = (float) plugin.getConfig().getDouble("telegram.freeze.walk_speed", 0.0);
        this.freezeFlySpeed = (float) plugin.getConfig().getDouble("telegram.freeze.fly_speed", 0.0);
        this.freezeInvulnerable = plugin.getConfig().getBoolean("telegram.freeze.invulnerable", true);
        this.freezeCollidable = plugin.getConfig().getBoolean("telegram.freeze.collidable", false);
        this.unlockWalkSpeed = (float) plugin.getConfig().getDouble("telegram.freeze.unlock.walk_speed", 0.2);
        this.unlockFlySpeed = (float) plugin.getConfig().getDouble("telegram.freeze.unlock.fly_speed", 0.1);
        this.unlockInvulnerable = plugin.getConfig().getBoolean("telegram.freeze.unlock.invulnerable", false);
        this.unlockCollidable = plugin.getConfig().getBoolean("telegram.freeze.unlock.collidable", true);
        this.kickPendingMessage = messages.msg("tg.freeze.kick-pending");
        this.kickDeniedMessage = messages.msg("tg.freeze.kick-denied");
    }

    private void applyFreeze(Player p) {
        frozen.add(p.getUniqueId());
        p.setWalkSpeed(freezeWalkSpeed);
        p.setFlySpeed(freezeFlySpeed);
        p.setInvulnerable(freezeInvulnerable);
        p.setCollidable(freezeCollidable);
        messages.send(p, "tg.freeze.pending", Map.of());
    }

    private void removeFreeze(Player p) {
        frozen.remove(p.getUniqueId());
        // вернуть дефолты
        p.setWalkSpeed(unlockWalkSpeed);
        p.setFlySpeed(unlockFlySpeed);
        p.setInvulnerable(unlockInvulnerable);
        p.setCollidable(unlockCollidable);
        messages.send(p, "tg.freeze.unlocked", Map.of());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("telegram.auth_on_join", true)) return;

        Player p = e.getPlayer();
        var link = links.findByPlayer(p.getUniqueId());
        if (link.isEmpty()) return; // не привязан — не требуем

        long cooldown = plugin.getConfig().getLong("telegram.cooldown_minutes", 60);
        if (sessions.isCooldownOk(p.getUniqueId(), cooldown)) {
            return; // недавно подтверждал — доверяем
        }

        long kickAfter = plugin.getConfig().getLong("telegram.kick_after_seconds", 120);
        var expires = Instant.now().plusSeconds(kickAfter);
        sessions.createPending(
                p.getUniqueId(),
                expires,
                p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : null
        );

        applyFreeze(p);

        // плановый кик
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (frozen.contains(p.getUniqueId())) {
                p.kickPlayer(kickPendingMessage);
            }
        }, kickAfter * 20L);

        // периодическая проверка одобрения (каждые 10 тиков ~ 0.5 сек)
        new BukkitRunnable() {
            @Override public void run() {
                var ok = sessions.isApproved(p.getUniqueId());
                if (ok.isPresent()) {
                    cancel();
                    if (ok.get()) removeFreeze(p);
                    else p.kickPlayer(kickDeniedMessage);
                    return;
                }
                if (!p.isOnline()) cancel();
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // подчистим, если вдруг остался замороженным
        frozen.remove(e.getPlayer().getUniqueId());
    }

    // Блокируем активность «замороженных»
    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId())) return;
        if (e.getTo() == null) { e.setCancelled(true); return; }
        var from = e.getFrom(); var to = e.getTo();
        if (from.getX()!=to.getX() || from.getY()!=to.getY() || from.getZ()!=to.getZ()) {
            e.setCancelled(true);
        }
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId())) return;
        if (plugin.getConfig().getBoolean("telegram.freeze.deny_chat", true)) {
            e.setCancelled(true);
        }
    }

    @EventHandler public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId())) return;
        var allowed = plugin.getConfig().getStringList("telegram.freeze.deny_commands_except");
        if (allowed != null) {
            String msg = e.getMessage().toLowerCase(Locale.ROOT);
            for (String a : allowed) {
                if (a != null && !a.isEmpty() && msg.startsWith(a.toLowerCase(Locale.ROOT))) return;
            }
        }
        e.setCancelled(true);
    }

    @EventHandler public void onBreak(BlockBreakEvent e){ if (frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e){ if (frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onInv(InventoryClickEvent e){
        if (e.getWhoClicked() instanceof Player p && frozen.contains(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler public void onDamage(EntityDamageEvent e){
        if (e.getEntity() instanceof Player p && frozen.contains(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler public void onPvp(EntityDamageByEntityEvent e){
        if (e.getDamager() instanceof Player p && frozen.contains(p.getUniqueId())) e.setCancelled(true);
    }
}
