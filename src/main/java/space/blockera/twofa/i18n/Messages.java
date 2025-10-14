package space.blockera.twofa.i18n;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class Messages {
    private final JavaPlugin plugin;
    private FileConfiguration cfg;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;
        for (String k : defaultKeys()) {
            if (!cfg.isSet(k)) {
                cfg.set(k, defaults(k));
                changed = true;
            }
        }
        if (changed) {
            try { cfg.save(file); } catch (IOException ignored) {}
        }
    }

    private String[] defaultKeys() {
        return new String[] {
                "prefix","help","no-perm","reloaded","only-ingame",
                "setup-created","qr-link","otpauth-copy","after-setup",
                "usage-confirm","need-setup-first","confirm-ok","confirm-bad",
                "status","usage-disable","usage-force-disable","not-setup",
                "disabled","disable-bad","force-disabled","force-disabled-player",
                "force-disable-not-found",
                "unknown","pending.prompt","blocked.command","blocked.chat",
                "tg.link.begin","tg.status.linked","tg.status.notLinked","tg.unlinked",
                "tg.freeze.pending","tg.freeze.unlocked","tg.freeze.kick-pending","tg.freeze.kick-denied"
        };
    }

    private Object defaults(String key) {
        return switch (key) {
            case "prefix" -> "&a[2FA]&r ";
            case "help" -> List.of(
                    "{prefix}&fДоступные команды:",
                    "{prefix}&7/2fa {setup} &f- начать настройку",
                    "{prefix}&7/2fa {confirm} <код> &f- подтвердить код",
                    "{prefix}&7/2fa {status} &f- показать состояние",
                    "{prefix}&7/2fa {disable} <код> &f- отключить 2FA",
                    "{prefix}&7/2fa {force_disable} <ник> &f- отключить 2FA игроку (админ)",
                    "{prefix}&7/2fa {telegram_link} &f- привязать Telegram",
                    "{prefix}&7/2fa {telegram_status} &f- статус Telegram",
                    "{prefix}&7/2fa {telegram_unlink} &f- отвязать Telegram",
                    "{prefix}&7/2fa {reload} &f- перезагрузить конфиг"
            );
            case "no-perm" -> "&cНедостаточно прав.";
            case "reloaded" -> "&aКонфиг и подключения перезагружены.";
            case "only-ingame" -> "&cКоманда доступна только из игры.";
            case "setup-created" -> "&aСекрет сгенерирован. Отсканируйте QR в приложении.";
            case "qr-link" -> "&7QR-ссылка: ";
            case "otpauth-copy" -> "&7Если QR не открывается, используйте otpauth URI: ";
            case "after-setup" -> "&7Затем: &f/2fa confirm <код>";
            case "usage-confirm" -> "&eИспользование: /2fa confirm <6-значный код>";
            case "need-setup-first" -> "&eСначала выполните /2fa setup.";
            case "confirm-ok" -> "&aУспех! 2FA подтверждена.";
            case "confirm-bad" -> "&cНеверный код. Проверьте время на устройстве.";
            case "status" -> "&7Включено: &f%s&7, подтверждено в сессии: &f%s";
            case "usage-disable" -> "&eДля отключения: /2fa disable <код>";
            case "usage-force-disable" -> "{prefix}&7Для отключения игроку: &f/2fa {force_disable} <ник>";
            case "not-setup" -> "&e2FA не настроена.";
            case "disabled" -> "&a2FA отключена.";
            case "disable-bad" -> "&cКод не подошёл, отключение отменено.";
            case "force-disabled" -> "{prefix}&a2FA отключена для {player}.";
            case "force-disabled-player" -> "{prefix}&cАдминистратор отключил вашу 2FA. Настройте заново через /2fa {setup}.";
            case "force-disable-not-found" -> "{prefix}&cИгрок {player} не найден или ни разу не заходил.";
            case "unknown" -> "&cНеизвестная подкоманда.";
            case "pending.prompt" -> "{prefix}&fВведите &a{confirm}&f из приложения.";
            case "blocked.command" -> "{prefix}&cДоступ запрещён до ввода кода. Используйте &a{confirm}";
            case "blocked.chat" -> "{prefix}&cЧат недоступен до подтверждения 2FA.";
            case "tg.link.begin" -> List.of(
                    "{prefix}Начинаем привязку Telegram.",
                    "{prefix}Открой бота {bot} и отправь ему код: &e{token}",
                    "{prefix}Ссылка: &b{url}",
                    "{prefix}После подтверждения ботом — вернись и проверь /2fa {telegram_status}"
            );
            case "tg.status.linked" -> "{prefix}&aПривязан к {tg}";
            case "tg.status.notLinked" -> "{prefix}&7Telegram ещё не привязан.";
            case "tg.unlinked" -> "{prefix}&eПривязка Telegram удалена.";
            case "tg.freeze.pending" -> "{prefix}&cПодтвердите вход в Telegram-боте, движение временно заблокировано.";
            case "tg.freeze.unlocked" -> "{prefix}&aВход подтверждён. Удачной игры!";
            case "tg.freeze.kick-pending" -> "{prefix}&cНе подтвержден вход в Telegram.";
            case "tg.freeze.kick-denied" -> "{prefix}&cВход отклонён через Telegram.";
            default -> "&c<missing message>";
        };
    }

    /** Простое сообщение по ключу, как раньше. */
    public String msg(String key) {
        String raw = cfg.getString(key, String.valueOf(defaults(key)));
        return color(raw);
    }

    /** Форматирование через String.format (как раньше). */
    public String fmt(String key, Object... args) {
        String pattern = cfg.getString(key, String.valueOf(defaults(key)));
        String text = String.format(Locale.ROOT, pattern, args);
        return color(text);
    }

    /** Новый метод: подстановка плейсхолдеров {name} + поддержка списков. */
    public List<String> render(String key, Map<String, String> vars) {
        List<String> lines;
        if (cfg.isList(key)) {
            lines = new ArrayList<>(cfg.getStringList(key));
        } else if (cfg.isSet(key)) {
            lines = List.of(Objects.requireNonNullElse(cfg.getString(key), key));
        } else {
            Object def = defaults(key);
            if (def instanceof List<?> l) {
                lines = new ArrayList<>();
                for (Object o : l) lines.add(String.valueOf(o));
            } else {
                lines = List.of(String.valueOf(def));
            }
        }

        String prefix = cfg.getString("prefix", String.valueOf(defaults("prefix")));
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            String s = line.replace("{prefix}", prefix);
            if (vars != null) {
                for (Map.Entry<String, String> e : vars.entrySet()) {
                    s = s.replace("{" + e.getKey() + "}", e.getValue());
                }
            }
            out.add(color(s));
        }
        return out;
    }

    /** Удобно слать прямо получателю. */
    public void send(CommandSender to, String key, Map<String, String> vars) {
        for (String line : render(key, vars)) {
            to.sendMessage(line);
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
