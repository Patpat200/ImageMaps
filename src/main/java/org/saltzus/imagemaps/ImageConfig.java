package org.saltzus.imagemaps;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;

/**
 * Charge data.yml sous la forme :
 *
 * ids:
 *   '5': https://exemple.com/image.png
 *   '6': https://exemple.com/autre.jpg
 *
 * Les clés sont les ids de MapView Bukkit. Les entrées dont l'URL est
 * manifestement vide/invalide sont ignorées avec un avertissement au
 * lieu de faire planter le chargement de tout le fichier.
 */
public class ImageConfig {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<Integer, String> ids = new TreeMap<>();

    public ImageConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        ids.clear();
        if (!file.exists()) {
            plugin.getLogger().info("[ImageMaps] Aucun data.yml trouvé, un fichier vide sera créé.");
            save();
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("ids");
        if (section == null) {
            plugin.getLogger().warning("[ImageMaps] data.yml ne contient pas de section 'ids'.");
            return;
        }

        int loaded = 0;
        int skipped = 0;
        for (String key : section.getKeys(false)) {
            String rawUrl = section.getString(key);
            Integer id = parseId(key);
            if (id == null) {
                plugin.getLogger().warning("[ImageMaps] Clé invalide ignorée dans data.yml : '" + key + "'");
                skipped++;
                continue;
            }
            if (rawUrl == null || rawUrl.isBlank()) {
                plugin.getLogger().warning("[ImageMaps] URL vide ignorée pour l'id " + id);
                skipped++;
                continue;
            }
            String cleaned = rawUrl.trim();
            if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
                plugin.getLogger().warning("[ImageMaps] URL non-HTTP(S) ignorée pour l'id " + id + " : " + cleaned);
                skipped++;
                continue;
            }
            ids.put(id, cleaned);
            loaded++;
        }
        plugin.getLogger().info("[ImageMaps] data.yml chargé : " + loaded + " image(s), " + skipped + " entrée(s) ignorée(s).");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<Integer, String> entry : ids.entrySet()) {
            yaml.set("ids." + entry.getKey(), entry.getValue());
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                //noinspection ResultOfMethodCallIgnored
                plugin.getDataFolder().mkdirs();
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "[ImageMaps] Impossible d'écrire data.yml", e);
        }
    }

    public String getUrl(int id) {
        return ids.get(id);
    }

    public void put(int id, String url) {
        ids.put(id, url);
        save();
    }

    public void remove(int id) {
        ids.remove(id);
        save();
    }

    public Map<Integer, String> all() {
        return ids;
    }

    public int nextFreeId() {
        int candidate = 0;
        while (ids.containsKey(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private static Integer parseId(String key) {
        try {
            return Integer.parseInt(key.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
