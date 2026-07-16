package org.saltzus.imagemaps;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
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

        // Les plugins Paper (paper-plugin.yml) enregistrent leurs commandes
        // via la Lifecycle API plutôt que via la section "commands:" du yml.
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(
                    "imagemap",
                    "Gère les cartes-images (ImageMaps)",
                    new ImageMapCommand(this)
            );
        });

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
