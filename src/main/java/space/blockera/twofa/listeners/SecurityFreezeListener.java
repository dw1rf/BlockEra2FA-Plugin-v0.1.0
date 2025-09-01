package space.blockera.twofa.listeners;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

import java.time.Instant;
import java.util.*;

public class SecurityFreezeListener implements Listener {
    private final Plugin plugin;
    private final TelegramLinkRepository links;
    private final TelegramSessionRepository sessions;
    private final Set<UUID> frozen = new HashSet<>();

    public SecurityFreezeListener(Plugin plugin, TelegramLinkRepository links, TelegramSessionRepository sessions) {
        this.plugin = plugin;
        this.links = links;
        this.sessions = sessions;
    }

    private void applyFreeze(Player p) {
        frozen.add(p.getUniqueId());
        p.setWalkSpeed((float) plugin.getConfig().getDouble("telegram.freeze.walk_speed", 0.0));
        p.setFlySpeed((float) plugin.getConfig().getDouble("telegram.freeze.fly_speed", 0.0));
        p.setInvulnerable(plugin.getConfig().getBoolean("telegram.freeze.invulnerable", true));
        // ВАЖНО: без инверсии — читаем как есть (по умолчанию false ⇒ не сталкивается)
        p.setCollidable(plugin.getConfig().getBoolean("telegram.freeze.collidable", false));
        p.sendMessage(ChatColor.RED + "Подтвердите вход в Telegram-боте, движение временно заблокировано.");
    }

    private void removeFreeze(Player p) {
        frozen.remove(p.getUniqueId());
        // вернуть дефолты
        p.setWalkSpeed(0.2f);
        p.setFlySpeed(0.1f);
        p.setInvulnerable(false);
        p.setCollidable(true);
        p.sendMessage(ChatColor.GREEN + "Вход подтверждён. Удачной игры!");
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
                p.kickPlayer(ChatColor.RED + "Не подтвержден вход в Telegram.");
            }
        }, kickAfter * 20L);

        // периодическая проверка одобрения (каждые 10 тиков ~ 0.5 сек)
        new BukkitRunnable() {
            @Override public void run() {
                var ok = sessions.isApproved(p.getUniqueId());
                if (ok.isPresent()) {
                    cancel();
                    if (ok.get()) removeFreeze(p);
                    else p.kickPlayer(ChatColor.RED + "Вход отклонён через Telegram.");
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
