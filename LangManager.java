package ru.rpcore.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Языки на основе plugins/RPCore/lang/&lt;код&gt;.yml и MiniMessage.
 *
 * <p>Порядок поиска ключа: выбранный язык -> язык по умолчанию -> сам ключ (как текст).
 * Каждый файл дополнительно подстрахован встроенной в jar версией, поэтому после
 * обновления плагина новые ключи работают, даже если на диске старый файл.</p>
 *
 * <p>Плейсхолдеры передаются парами: {@code "name", "Steve", "count", "5"} и пишутся в
 * тексте как {@code <name>}. Значения вставляются как обычный текст (теги в них не
 * разбираются). Тег {@code <prefix>} доступен всегда.</p>
 *
 * <p>Язык конкретного игрока класс не знает - его подставляет кеш игроков (шаг 5).</p>
 */
public final class LangManager {

    /** Языки, которые плагин кладёт на диск при первом запуске. */
    private static final List<String> BUNDLED = List.of("ru", "en");

    private final Plugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private volatile Map<String, YamlConfiguration> langs = Map.of();
    private volatile String defaultCode = "ru";

    public LangManager(Plugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ loading

    /** Загрузить (или перезагрузить) все языки. Вызывать из onEnable и при /rpcore reload. */
    public void reload(String defaultLanguage) {
        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Не удалось создать папку lang/");
        }

        for (String code : BUNDLED) {
            if (!new File(dir, code + ".yml").exists()) {
                plugin.saveResource("lang/" + code + ".yml", false);
            }
        }

        Map<String, YamlConfiguration> loaded = new ConcurrentHashMap<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String code = file.getName().substring(0, file.getName().length() - 4).toLowerCase();
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

                // Запасной слой: версия из jar (если такая есть)
                try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
                    if (in != null) {
                        cfg.setDefaults(YamlConfiguration.loadConfiguration(
                                new InputStreamReader(in, StandardCharsets.UTF_8)));
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("Не удалось прочитать встроенный язык " + code + ": " + ex.getMessage());
                }
                loaded.put(code, cfg);
            }
        }

        String wanted = defaultLanguage.toLowerCase();
        if (!loaded.containsKey(wanted)) {
            String fallback = loaded.containsKey("en") ? "en" : loaded.keySet().stream().findFirst().orElse(wanted);
            plugin.getLogger().warning("Язык по умолчанию '" + wanted + "' не найден, используется '" + fallback + "'");
            wanted = fallback;
        }

        this.langs = loaded;
        this.defaultCode = wanted;
        plugin.getLogger().info("Языки загружены: " + available() + " (по умолчанию: " + wanted + ")");
    }

    // ------------------------------------------------------------ info

    public String defaultCode() {
        return defaultCode;
    }

    /** Коды доступных языков по алфавиту. */
    public Set<String> available() {
        return Collections.unmodifiableSet(new TreeSet<>(langs.keySet()));
    }

    public boolean has(String code) {
        return code != null && langs.containsKey(code.toLowerCase());
    }

    /** Красивое имя языка из ключа meta.name (или сам код). */
    public String displayName(String code) {
        YamlConfiguration cfg = langs.get(code);
        return cfg == null ? code : cfg.getString("meta.name", code);
    }

    /** Привести код к существующему языку, иначе вернуть язык по умолчанию. */
    public String resolve(String code) {
        return has(code) ? code.toLowerCase() : defaultCode;
    }

    // ------------------------------------------------------------ messages

    /** Сырой текст ключа (одна строка; для списка - строки через перенос). Null, если ключа нет нигде. */
    public String raw(String lang, String key) {
        List<String> lines = rawLines(lang, key);
        return lines == null ? null : String.join("\n", lines);
    }

    private List<String> rawLines(String lang, String key) {
        Map<String, YamlConfiguration> snapshot = langs;
        for (String code : new String[]{lang, defaultCode}) {
            YamlConfiguration cfg = code == null ? null : snapshot.get(code);
            if (cfg == null || !cfg.contains(key)) continue;
            if (cfg.isList(key)) {
                return cfg.getStringList(key);
            }
            String value = cfg.getString(key);
            if (value != null) return List.of(value);
        }
        return null;
    }

    /** Строки сообщения как компоненты. Если ключ не найден - одна строка с самим ключом. */
    public List<Component> lines(String lang, String key, String... placeholders) {
        List<String> raw = rawLines(lang, key);
        if (raw == null) {
            return List.of(Component.text(key));
        }
        TagResolver resolver = resolver(lang, placeholders);
        List<Component> result = new ArrayList<>(raw.size());
        for (String line : raw) {
            result.add(mm.deserialize(line, resolver));
        }
        return result;
    }

    /** Сообщение одним компонентом (многострочное склеивается переносами - удобно для кика). */
    public Component get(String lang, String key, String... placeholders) {
        return Component.join(JoinConfiguration.newlines(), lines(lang, key, placeholders));
    }

    /** Отправить сообщение получателю на указанном языке. Безопасно вызывать из любого потока. */
    public void send(CommandSender to, String lang, String key, String... placeholders) {
        for (Component line : lines(lang, key, placeholders)) {
            to.sendMessage(line);
        }
    }

    // ------------------------------------------------------------ internals

    private TagResolver resolver(String lang, String... kv) {
        TagResolver.Builder builder = TagResolver.builder();
        String prefix = raw(lang, "prefix");
        builder.resolver(Placeholder.parsed("prefix", prefix == null ? "" : prefix));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            builder.resolver(Placeholder.unparsed(kv[i], String.valueOf(kv[i + 1])));
        }
        return builder.build();
    }
}
