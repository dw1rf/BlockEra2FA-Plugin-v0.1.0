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
                "status","usage-disable","not-setup","disabled","disable-bad",
                "unknown",
                // ↓ добавим дефолты для привязки Telegram
                "tg.link.begin"
        };
    }

    private Object defaults(String key) {
        return switch (key) {
            case "prefix" -> "&a[2FA]&r ";
            case "help" -> "%s | %s | %s | %s | %s";
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
            case "not-setup" -> "&e2FA не настроена.";
            case "disabled" -> "&a2FA отключена.";
            case "disable-bad" -> "&cКод не подошёл, отключение отменено.";
            case "unknown" -> "&cНеизвестная подкоманда.";
            case "tg.link.begin" -> List.of(
                    "{prefix}Начинаем привязку Telegram.",
                    "{prefix}Открой бота {bot} и отправь ему код: &e{token}",
                    "{prefix}Ссылка: &b{url}",
                    "{prefix}После подтверждения ботом — вернись и проверь /2fa tgstatus"
            );
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
