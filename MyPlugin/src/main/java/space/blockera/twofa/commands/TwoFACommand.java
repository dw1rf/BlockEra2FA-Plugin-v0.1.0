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

    public TwoFACommand(Plugin plugin,
                        UserRepository repo,
                        TotpService totp,
                        SessionService sessions,
                        CryptoUtil crypto,
                        Messages msg,
                        TelegramLinkRepository tgLinks,
                        ChallengeRepository challenges) {
        this.plugin = plugin;
        this.repo = repo;
        this.totp = totp;
        this.sessions = sessions;
        this.crypto = crypto;
        this.messages = msg;
        this.tgLinks = tgLinks;
        this.challenges = challenges;
    }

    public void rewire(UserRepository repo,
                       TotpService totp,
                       SessionService sessions,
                       CryptoUtil crypto,
                       Messages msg,
                       TelegramLinkRepository tgLinks,
                       ChallengeRepository challenges) {
        this.repo = repo;
        this.totp = totp;
        this.sessions = sessions;
        this.crypto = crypto;
        this.messages = msg;
        this.tgLinks = tgLinks;
        this.challenges = challenges;
    }

    private static String genToken16() {
        // 16-символьный верхний регистр, как ожидает твой бот
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = messages.msg("prefix");

        if (args.length == 0) {
            sender.sendMessage(messages.fmt("help",
                    "/2fa setup",
                    "/2fa confirm <код>",
                    "/2fa status",
                    "/2fa disable <код>",
                    "/2fa reload"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload": {
                if (!sender.hasPermission("blockera.twofa.admin")) {
                    sender.sendMessage(messages.msg("no-perm"));
                    return true;
                }
                if (plugin instanceof BlockEraTwoFAPlugin main) {
                    try {
                        main.reloadCore();
                        rewire(main.getUserRepository(), main.getTotpService(), main.getSessionService(),
                                main.getCrypto(), main.getMessages(),
                                main.getTelegramLinks(), main.getChallenges());
                        sender.sendMessage(messages.msg("reloaded"));
                    } catch (Exception ex) {
                        sender.sendMessage(prefix + "Ошибка при перезагрузке: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }
                return true;
            }

            // ===================== TELEGRAM LINK =====================
            case "linktelegram":
            case "link": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(messages.msg("only-ingame"));
                    return true;
                }

                if (tgLinks.findByPlayer(p.getUniqueId()).isPresent()) {
                    // уже привязан
                    messages.send(p, "tg.alreadyLinked", Map.of());
                    return true;
                }

                // генерим токен и TTL
                String token = genToken16();
                int ttlSec = plugin.getConfig().getInt("telegram.challenge_ttl_seconds", 600);
                challenges.create(p.getUniqueId(), token, Instant.ofEpochMilli(System.currentTimeMillis() + ttlSec * 1000L));

                // берём имя бота и делаем глубокую ссылку
                String botRaw = plugin.getConfig().getString("telegram.bot_username", "BlockEraAuthBot");
                String bot = botRaw != null && botRaw.startsWith("@") ? botRaw.substring(1) : botRaw;
                String linkTpl = plugin.getConfig().getString("telegram.link_template", "https://t.me/%s?start=%s");
                String url = String.format(Locale.ROOT, linkTpl, bot, token);

                // выводим готовое сообщение через Messages (подстановка {prefix}/{bot}/{token}/{url})
                messages.send(p, "tg.link.begin", Map.of(
                        "bot", "@" + bot,
                        "token", token,
                        "url", url
                ));

                // дополнительная кликабельная строка (приятная мелочь)
                p.sendMessage(Component.text(" ")
                        .append(Component.text("Открыть бота: ").append(Component.text("@" + bot)
                                .clickEvent(ClickEvent.openUrl("https://t.me/" + bot))))
                        .append(Component.text("  •  "))
                        .append(Component.text("Вставить токен").clickEvent(ClickEvent.copyToClipboard(token)))
                );

                return true;
            }

            case "tgstatus": {
                if (!(sender instanceof Player p)) { sender.sendMessage(messages.msg("only-ingame")); return true; }
                var link = tgLinks.findByPlayer(p.getUniqueId());
                if (link.isPresent()) {
                    // именованный плейсхолдер {tg} через messages.send
                    messages.send(p, "tg.status.linked",
                            Map.of("tg", "@" + link.get().telegramUsername()));
                } else {
                    messages.send(p, "tg.status.notLinked", Map.of());
                }
                return true;
            }

            case "unlinktelegram": {
                if (!(sender instanceof Player p)) { sender.sendMessage(messages.msg("only-ingame")); return true; }
                tgLinks.deleteByPlayer(p.getUniqueId());
                messages.send(p, "tg.unlinked", Map.of());
                return true;
            }

            // ===================== TOTP FLOW =====================
            case "setup": {
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

            case "confirm": {
                if (!(sender instanceof Player p)) { sender.sendMessage(messages.msg("only-ingame")); return true; }
                if (args.length < 2) { p.sendMessage(messages.msg("usage-confirm")); return true; }
                var enc = repo.getSecret(p.getUniqueId());
                if (enc.isEmpty()) { p.sendMessage(messages.msg("need-setup-first")); return true; }
                String base32 = crypto.reveal(enc.get());
                if (totp.verifyCode(base32, args[1])) {
                    repo.setEnabled(p.getUniqueId(), true);
                    sessions.markVerified(p.getUniqueId());
                    p.sendMessage(messages.msg("confirm-ok"));
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player pl = Bukkit.getPlayer(p.getUniqueId());
                        if (pl != null) {
                            pl.setWalkSpeed(0.2f);
                            pl.setFlySpeed(0.1f);
                            pl.setInvulnerable(false);
                            pl.setCollidable(true);
                        }
                    });
                } else {
                    p.sendMessage(messages.msg("confirm-bad"));
                }
                return true;
            }

            case "status": {
                if (!(sender instanceof Player p)) { sender.sendMessage(messages.msg("only-ingame")); return true; }
                boolean enabled = repo.isEnabled(p.getUniqueId());
                boolean verified = sessions.isVerified(p.getUniqueId());
                p.sendMessage(messages.fmt("status", enabled, verified));
                return true;
            }

            case "disable": {
                if (!(sender instanceof Player p)) { sender.sendMessage(messages.msg("only-ingame")); return true; }
                if (args.length < 2) { p.sendMessage(messages.msg("usage-disable")); return true; }
                var enc = repo.getSecret(p.getUniqueId());
                if (enc.isEmpty()) { p.sendMessage(messages.msg("not-setup")); return true; }
                String base32 = crypto.reveal(enc.get());
                if (totp.verifyCode(base32, args[1])) {
                    repo.upsertSecret(p.getUniqueId(), null, false);
                    p.sendMessage(messages.msg("disabled"));
                } else {
                    p.sendMessage(messages.msg("disable-bad"));
                }
                return true;
            }

            default:
                sender.sendMessage(messages.msg("unknown"));
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("setup","confirm","status","disable","reload","linktelegram","link","tgstatus","unlinktelegram");
        }
        return Collections.emptyList();
    }
}
