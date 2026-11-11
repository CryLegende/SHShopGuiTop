package org.Cry_Legende.sHShopGuiTop;

import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Textes de messages.yml, codes couleur &amp;. Une clé absente du fichier prend le texte du jar, une clé vide
 * désactive le message.
 */
final class Messages {

    private final Map<String, String> texts;

    private Messages(Map<String, String> texts) {
        this.texts = texts;
    }

    /** Crée messages.yml depuis le jar s'il n'existe pas. */
    static void prepare(JavaPlugin plugin) {
        if (!new File(plugin.getDataFolder(), "messages.yml").exists()) {
            plugin.saveResource("messages.yml", false);
        }
    }

    /** Lit messages.yml ; lève l'erreur YAML telle quelle pour que l'appelant garde l'ancien état. */
    static Messages load(JavaPlugin plugin) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(plugin.getDataFolder(), "messages.yml"));
        return merge(yaml, jarDefaults(plugin));
    }

    /** Textes du jar seuls, quand messages.yml est illisible au démarrage. */
    static Messages defaults(JavaPlugin plugin) {
        return merge(new YamlConfiguration(), jarDefaults(plugin));
    }

    private static Messages merge(YamlConfiguration file, YamlConfiguration defaults) {
        Set<String> keys = new LinkedHashSet<>(defaults.getKeys(false));
        keys.addAll(file.getKeys(false));
        Map<String, String> texts = new HashMap<>();
        for (String key : keys) {
            String value = file.isString(key) ? file.getString(key) : defaults.getString(key);
            if (value != null && !value.isEmpty()) {
                texts.put(key, value);
            }
        }
        return new Messages(texts);
    }

    private static YamlConfiguration jarDefaults(JavaPlugin plugin) {
        try (InputStream in = plugin.getResource("messages.yml")) {
            return in == null ? new YamlConfiguration()
                    : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    /** Texte coloré de la clé, ou null s'il est absent ou vide. Chaque paire de {@code pairs} remplace un {nom}. */
    String get(String key, String... pairs) {
        String text = texts.get(key);
        if (text == null) {
            return null;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
