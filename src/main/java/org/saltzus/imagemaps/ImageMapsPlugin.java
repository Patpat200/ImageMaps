package org.saltzus.imagemaps;

import org.bukkit.plugin.java.JavaPlugin;

public class ImageMapsPlugin extends JavaPlugin {

    private ImageConfig imageConfig;
    private ImageCache imageCache;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }

        this.imageConfig = new ImageConfig(this);
        this.imageConfig.load();

        this.imageCache = new ImageCache(this);

        getServer().getPluginManager().registerEvents(new ImageManager(this), this);
        var cmd = getCommand("imagemap");
        if (cmd != null) {
            cmd.setExecutor(new ImageMapCommand(this));
        } else {
            getLogger().warning("Commande 'imagemap' introuvable dans paper-plugin.yml ?");
        }

        getLogger().info("[ImageMaps] Activé — " + imageConfig.all().size() + " image(s) configurée(s), chargement en tâche de fond.");
    }

    @Override
    public void onDisable() {
        getLogger().info("[ImageMaps] Désactivé.");
    }

    public ImageConfig getImageConfig() {
        return imageConfig;
    }

    public ImageCache getImageCache() {
        return imageCache;
    }
}
