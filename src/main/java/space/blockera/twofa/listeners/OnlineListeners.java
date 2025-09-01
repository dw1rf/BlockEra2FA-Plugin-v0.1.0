// src/main/java/space/blockera/twofa/listeners/OnlineListeners.java
package space.blockera.twofa.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitRunnable;
import space.blockera.twofa.BlockEraTwoFAPlugin;
import space.blockera.twofa.storage.OnlineRepository;

import java.sql.Connection;
import java.util.UUID;

public class OnlineListeners implements Listener {
    private final BlockEraTwoFAPlugin plugin;
    private final OnlineRepository repo;
    private final String serverName;

    public OnlineListeners(BlockEraTwoFAPlugin plugin, OnlineRepository repo) {
        this.plugin = plugin;
        this.repo = repo;
        this.serverName = Bukkit.getServer().getName(); // или возьми из config.yml
        startHeartbeat();     // периодическое обновление last_seen/online
        startLogoutWorker();  // обработчик очереди tg_actions
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        try {
            repo.upsertOnline(p.getUniqueId(), p.getName(), true,
                    p.getWorld().getName(), serverName);
        } catch (Exception ignored) {}
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        try { repo.markOffline(e.getPlayer().getUniqueId()); } catch (Exception ignored) {}
    }

    @EventHandler
    public void onChangeWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        try {
            repo.upsertOnline(p.getUniqueId(), p.getName(), true,
                    p.getWorld().getName(), serverName);
        } catch (Exception ignored) {}
    }

    private void startHeartbeat() {
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try {
                        repo.upsertOnline(p.getUniqueId(), p.getName(), true,
                                p.getWorld().getName(), serverName);
                    } catch (Exception ignored) {}
                }
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 30, 20L * 30); // каждые 30 сек
    }

    private void startLogoutWorker() {
        new BukkitRunnable() {
            @Override public void run() {
                try (Connection c = plugin.getDataSource().getConnection()) {
                    var rs = repo.fetchPendingLogout(c);
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        UUID u = UUID.fromString(rs.getString("player_uuid"));
                        Player p = Bukkit.getPlayer(u);
                        if (p != null && p.isOnline()) {
                            p.kickPlayer("Вы вышли из игры через Telegram.");
                        }
                        repo.markProcessed(id);
                    }
                } catch (Exception ignored) {}
            }
        }.runTaskTimerAsynchronously(plugin, 20L * 2, 20L * 2); // каждые 2 сек
    }
}
