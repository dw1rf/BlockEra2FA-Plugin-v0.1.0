package space.blockera.twofa.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import space.blockera.twofa.BlockEraTwoFAPlugin;
import space.blockera.twofa.i18n.Messages;
import space.blockera.twofa.security.CryptoUtil;
import space.blockera.twofa.session.SessionService;
import space.blockera.twofa.session.TrustedDeviceService;
import space.blockera.twofa.storage.UserRepository;
import space.blockera.twofa.totp.TotpService;
import space.blockera.twofa.storage.ChallengeRepository;
import space.blockera.twofa.storage.TelegramLinkRepository;

import java.time.Instant;
import java.util.*;

public class TwoFACommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private UserRepository repo;
    private TotpService totp;
    private SessionService sessions;
    private CryptoUtil crypto;
    private Messages messages;
    private TelegramLinkRepository tgLinks;
    private ChallengeRepository challenges;
    private TrustedDeviceService trustedDevices;
    private List<String> setupAliases = List.of("setup");
    private List<String> confirmAliases = List.of("confirm");
    private List<String> statusAliases = List.of("status");
    private List<String> disableAliases = List.of("disable");
    private List<String> forceDisableAliases = List.of("force-disable");
    private List<String> reloadAliases = List.of("reload");
    private List<String> tgLinkAliases = List.of("link");
    private List<String> tgStatusAliases = List.of("tgstatus");
    private List<String> tgUnlinkAliases = List.of("unlinktelegram");

    public TwoFACommand(Plugin plugin,
                        UserRepository repo,
                        TotpService totp,
                        SessionService sessions,
                        CryptoUtil crypto,
                        Messages msg,
                        TelegramLinkRepository tgLinks,
                        ChallengeRepository challenges,
                        TrustedDeviceService trustedDevices) {
        this.plugin = plugin;
        this.repo = repo;
        this.totp = totp;
        this.sessions = sessions;
        this.crypto = crypto;
        this.messages = msg;
        this.tgLinks = tgLinks;
        this.challenges = challenges;
        this.trustedDevices = trustedDevices;
        reloadSettings();
    }

    public void rewire(UserRepository repo,
                       TotpService totp,
                       SessionService sessions,
                       CryptoUtil crypto,
                       Messages msg,
                       TelegramLinkRepository tgLinks,
                       ChallengeRepository challenges,
                       TrustedDeviceService trustedDevices) {
        this.repo = repo;
        this.totp = totp;
        this.sessions = sessions;
        this.crypto = crypto;
        this.messages = msg;
        this.tgLinks = tgLinks;
        this.challenges = challenges;
        this.trustedDevices = trustedDevices;
    }

    public void reloadSettings() {
        this.setupAliases = readAliases("commands.setup", "setup");
        this.confirmAliases = readAliases("commands.confirm", "confirm");
        this.statusAliases = readAliases("commands.status", "status");
        this.disableAliases = readAliases("commands.disable", "disable");
        this.forceDisableAliases = readAliases("commands.force_disable", "force-disable");
        this.reloadAliases = readAliases("commands.reload", "reload");
        this.tgLinkAliases = readAliases("commands.telegram_link", "link");
        this.tgStatusAliases = readAliases("commands.telegram_status", "tgstatus");
        this.tgUnlinkAliases = readAliases("commands.telegram_unlink", "unlinktelegram");
    }

    private List<String> readAliases(String path, String fallback) {
        List<String> raw = plugin.getConfig().getStringList(path);
        if (raw == null || raw.isEmpty()) {
            return List.of(fallback);
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null) continue;
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            set.add(trimmed.toLowerCase(Locale.ROOT));
        }
        if (set.isEmpty()) {
            set.add(fallback);
        }
        return List.copyOf(set);
    }

    private String primary(List<String> aliases, String fallback) {
        return aliases.isEmpty() ? fallback : aliases.get(0);
    }

    private Map<String, String> helpPlaceholders() {
        return Map.of(
                "setup", primary(setupAliases, "setup"),
                "confirm", primary(confirmAliases, "confirm"),
                "status", primary(statusAliases, "status"),
                "disable", primary(disableAliases, "disable"),
                "force_disable", primary(forceDisableAliases, "force-disable"),
                "reload", primary(reloadAliases, "reload"),
                "telegram_link", primary(tgLinkAliases, "link"),
                "telegram_status", primary(tgStatusAliases, "tgstatus"),
                "telegram_unlink", primary(tgUnlinkAliases, "unlinktelegram")
        );
    }

    private Map<String, String> basePlaceholders() {
        return new HashMap<>(helpPlaceholders());
    }

    private static String genToken16() {
        // 16-символьный верхний регистр, как ожидает твой бот
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = messages.msg("prefix");

        if (args.length == 0) {
            messages.send(sender, "help", helpPlaceholders());
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (reloadAliases.contains(sub)) {
                if (!sender.hasPermission("blockera.twofa.admin")) {
                    sender.sendMessage(messages.msg("no-perm"));
                    return true;
                }
                if (plugin instanceof BlockEraTwoFAPlugin main) {
                    try {
                        main.reloadCore();
                        rewire(main.getUserRepository(), main.getTotpService(), main.getSessionService(),
                                main.getCrypto(), main.getMessages(),
                                main.getTelegramLinks(), main.getChallenges(),
                                main.getTrustedDeviceService());
                        sender.sendMessage(messages.msg("reloaded"));
                    } catch (Exception ex) {
                        sender.sendMessage(prefix + "Ошибка при перезагрузке: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }
                return true;
        }

        // ===================== TELEGRAM LINK =====================
        if (tgLinkAliases.contains(sub)) {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(messages.msg("only-ingame"));
                    return true;
                }

                if (tgLinks.findByPlayer(p.getUniqueId()).isPresent()) {
                    // уже привязан
                    messages.send(p, "tg.alreadyLinked", helpPlaceholders());
                    return true;
                }

                // генерим токен и TTL
                String token = genToken16();
                int ttlSec = plugin.getConfig().getInt("telegram.challenge_ttl_seconds", 600);
                challenges.create(p.getUniqueId(), p.getName(), token,
        Instant.ofEpochMilli(System.currentTimeMillis() + ttlSec * 1000L));


                // берём имя бота и делаем глубокую ссылку
                String botRaw = plugin.getConfig().getString("telegram.bot_username", "BlockEraAuthBot");
                String bot = botRaw != null && botRaw.startsWith("@") ? botRaw.substring(1) : botRaw;
                String linkTpl = plugin.getConfig().getString("telegram.link_template", "https://t.me/%s?start=%s");
                String url = String.format(Locale.ROOT, linkTpl, bot, token);

                // выводим готовое сообщение через Messages (подстановка {prefix}/{bot}/{token}/{url})
                Map<String, String> vars = basePlaceholders();
                vars.put("bot", "@" + bot);
                vars.put("token", token);
                vars.put("url", url);
                messages.send(p, "tg.link.begin", vars);

                // дополнительная кликабельная строка (приятная мелочь)
                p.sendMessage(Component.text(" ")
                        .append(Component.text("Открыть бота: ").append(Component.text("@" + bot)
                                .clickEvent(ClickEvent.openUrl("https://t.me/" + bot))))
                        .append(Component.text("  •  "))
                        .append(Component.text("Вставить токен").clickEvent(ClickEvent.copyToClipboard(token)))
                );

                return true;
        }

        if (tgStatusAliases.contains(sub)) {
                if (!(sender instanceof Player p)) { sender.sendMessage(messages.msg("only-ingame")); return true; }
                var link = tgLinks.findByPlayer(p.getUniqueId());
                if (link.isPresent()) {
                    Map<String, String> vars = basePlaceholders();
                    vars.put("tg", "@" + link.get().telegramUsername());
                    messages.send(p, "tg.status.linked", vars);
                } else {
                    messages.send(p, "tg.status.notLinked", helpPlaceholders());
                }
                return true;
        }

        if (tgUnlinkAliases.contains(sub)) {
                if (!(sender instanceof Player p)) { sender.sendMessage(messages.msg("only-ingame")); return true; }
                tgLinks.deleteByPlayer(p.getUniqueId());
                messages.send(p, "tg.unlinked", helpPlaceholders());
                return true;
        }

        // ===================== TOTP FLOW =====================
        if (setupAliases.contains(sub)) {
                if (!(sender instanceof Player p)) { sender.sendMessage(messages.msg("only-ingame")); return true; }
                String base32 = totp.generateBase32Secret();
                String otpauth = totp.buildOtpAuthUri(p.getName(), base32);
                String qrTpl = plugin.getConfig().getString("ui.qr_link_template");
                String qr = (qrTpl != null && !qrTpl.isEmpty()) ? totp.buildQrLink(otpauth, qrTpl) : null;

                repo.upsertSecret(p.getUniqueId(), crypto.protect(base32), false);
                p.sendMessage(messages.msg("setup-created"));
                if (qr != null) {
                    p.sendMessage(Component.text(messages.msg("qr-link"))
                            .append(Component.text(qr).clickEvent(ClickEvent.openUrl(qr))));
                }
                p.sendMessage(Component.text(messages.msg("otpauth-copy"))
                        .append(Component.text(otpauth).clickEvent(ClickEvent.copyToClipboard(otpauth))));
                p.sendMessage(messages.msg("after-setup"));
                sessions.markPending(p.getUniqueId());
                return true;
        }

        if (confirmAliases.contains(sub)) {
                if (!(sender instanceof Player p)) { sender.sendMessage(messages.msg("only-ingame")); return true; }
                if (args.length < 2) { p.sendMessage(messages.msg("usage-confirm")); return true; }
                var enc = repo.getSecret(p.getUniqueId());
                if (enc.isEmpty()) { p.sendMessage(messages.msg("need-setup-first")); return true; }
                String base32 = crypto.reveal(enc.get());
                if (totp.verifyCode(base32, args[1])) {
                    repo.setEnabled(p.getUniqueId(), true);
                    sessions.markVerified(p.getUniqueId(), SessionService.currentIp(p));
                    if (trustedDevices != null) {
                        trustedDevices.remember(p);
                    }
                    p.sendMessage(messages.msg("confirm-ok"));
                    if (trustedDevices != null && trustedDevices.isEnabled()) {
                        Map<String, String> vars = basePlaceholders();
                        vars.put("days", Long.toString(Math.max(1L, trustedDevices.ttlDays())));
                        messages.send(p, "trusted.remembered", vars);
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player pl = Bukkit.getPlayer(p.getUniqueId());
                        if (pl != null) {
                            var cfg = plugin.getConfig();
                            pl.setWalkSpeed((float) cfg.getDouble("ui.unlock.walk_speed", 0.2));
                            pl.setFlySpeed((float) cfg.getDouble("ui.unlock.fly_speed", 0.1));
                            pl.setInvulnerable(cfg.getBoolean("ui.unlock.invulnerable", false));
                            pl.setCollidable(cfg.getBoolean("ui.unlock.collidable", true));
                        }
                    });
                } else {
                    p.sendMessage(messages.msg("confirm-bad"));
                }
                return true;
        }

        if (statusAliases.contains(sub)) {
                if (!(sender instanceof Player p)) { sender.sendMessage(messages.msg("only-ingame")); return true; }
                boolean enabled = repo.isEnabled(p.getUniqueId());
                boolean verified = sessions.isVerified(p.getUniqueId());
                p.sendMessage(messages.fmt("status", enabled, verified));
                return true;
        }

        if (disableAliases.contains(sub)) {
                if (!(sender instanceof Player p)) { sender.sendMessage(messages.msg("only-ingame")); return true; }
                if (args.length < 2) { p.sendMessage(messages.msg("usage-disable")); return true; }
                var enc = repo.getSecret(p.getUniqueId());
                if (enc.isEmpty()) { p.sendMessage(messages.msg("not-setup")); return true; }
                String base32 = crypto.reveal(enc.get());
                if (totp.verifyCode(base32, args[1])) {
                    repo.upsertSecret(p.getUniqueId(), null, false);
                    if (trustedDevices != null) {
                        trustedDevices.forget(p.getUniqueId());
                    }
                    p.sendMessage(messages.msg("disabled"));
                } else {
                    p.sendMessage(messages.msg("disable-bad"));
                }
                return true;
        }

        if (forceDisableAliases.contains(sub)) {
                if (!sender.hasPermission("blockera.twofa.admin")) { sender.sendMessage(messages.msg("no-perm")); return true; }
                if (args.length < 2) {
                    messages.send(sender, "usage-force-disable", basePlaceholders());
                    return true;
                }
                String targetName = args[1];
                Player online = Bukkit.getPlayerExact(targetName);
                var target = online != null ? online : Bukkit.getOfflinePlayer(targetName);
                if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
                    Map<String, String> vars = basePlaceholders();
                    vars.put("player", targetName);
                    messages.send(sender, "force-disable-not-found", vars);
                    return true;
                }

                repo.upsertSecret(target.getUniqueId(), null, false);
                sessions.clear(target.getUniqueId());
                if (trustedDevices != null) {
                    trustedDevices.forget(target.getUniqueId());
                }

                String resolvedName = target.getName() != null ? target.getName() : targetName;
                Map<String, String> vars = basePlaceholders();
                vars.put("player", resolvedName);
                messages.send(sender, "force-disabled", vars);

                if (target.isOnline()) {
                    Player player = target.getPlayer();
                    if (player != null) {
                        messages.send(player, "force-disabled-player", basePlaceholders());
                    }
                }
                return true;
        }

        sender.sendMessage(messages.msg("unknown"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            LinkedHashSet<String> suggestions = new LinkedHashSet<>();
            suggestions.addAll(setupAliases);
            suggestions.addAll(confirmAliases);
            suggestions.addAll(statusAliases);
            suggestions.addAll(disableAliases);
            suggestions.addAll(reloadAliases);
            suggestions.addAll(forceDisableAliases);
            suggestions.addAll(tgLinkAliases);
            suggestions.addAll(tgStatusAliases);
            suggestions.addAll(tgUnlinkAliases);
            return new ArrayList<>(suggestions);
        }
        return Collections.emptyList();
    }
}
