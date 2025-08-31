package space.blockera.twofa.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import space.blockera.twofa.session.SessionService;
import space.blockera.twofa.storage.UserRepository;

import java.util.Set;
import java.util.UUID;

public class SecurityListeners implements Listener {
    private final Plugin plugin;
    private final UserRepository repo;
    private final SessionService sessions;
    private final String requiredPerm;
    private final Set<String> allowedWhenPending;
    private final Component prefix;

    public SecurityListeners(Plugin plugin, UserRepository repo, SessionService sessions) {
        this.plugin = plugin; this.repo = repo; this.sessions = sessions;
        this.requiredPerm = plugin.getConfig().getString("policy.required_permission", "blockera.twofa.required");
        this.allowedWhenPending = Set.copyOf(plugin.getConfig().getStringList("ui.allow_commands_when_pending"));
        this.prefix = Component.text(plugin.getConfig().getString("ui.prefix", "[2FA] "));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        boolean must = p.hasPermission(requiredPerm);
        boolean enabled = repo.isEnabled(u);
        if (must && enabled && !sessions.isVerified(u)) {
            sessions.markPending(u);
            freeze(p);
            p.sendMessage(prefix.append(Component.text("Введите /2fa confirm <код> из приложения-аутентификатора")));
        }
    }

    private boolean isLocked(Player p) {
        UUID u = p.getUniqueId();
        return sessions.isPending(u) && !sessions.isVerified(u);
    }

    private void freeze(Player p) {
        p.setWalkSpeed(0f);
        p.setFlySpeed(0f);
        p.setInvulnerable(true);
        p.setCollidable(false);
    }

    private void unfreeze(Player p) {
        p.setWalkSpeed(0.2f);
        p.setFlySpeed(0.1f);
        p.setInvulnerable(false);
        p.setCollidable(true);
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
        if (allowedWhenPending.stream().anyMatch(a -> a.equalsIgnoreCase(base))) return;
        e.setCancelled(true);
        p.sendMessage(prefix.append(Component.text("Доступ запрещён до ввода кода. Используйте /2fa.")));
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!isLocked(p)) return;
        e.setCancelled(true);
        p.sendMessage(prefix.append(Component.text("Чат недоступен до подтверждения 2FA.")));
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