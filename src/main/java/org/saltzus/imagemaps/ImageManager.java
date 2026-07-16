package org.saltzus.imagemaps;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.event.server.MapInitializeEvent;

import java.util.List;

/**
 * À chaque initialisation d'une MapView (au chargement du monde, ou à la
 * première consultation d'une carte), on vérifie si son id est référencé
 * dans data.yml, et si oui on lui attache notre renderer.
 */
public class ImageManager implements Listener {

    private final ImageMapsPlugin plugin;

    public ImageManager(ImageMapsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onMapInitEvent(MapInitializeEvent event) {
        MapView view = event.getMap();
        int id = view.getId();

        String url = plugin.getImageConfig().getUrl(id);
        if (url == null) {
            return; // pas une de nos cartes-images, on ne touche à rien
        }

        // Retire d'éventuels anciens renderers vanilla (fond de carte, joueur, etc.)
        // pour n'avoir que notre image en plein cadre.
        List<MapRenderer> existing = view.getRenderers();
        for (MapRenderer r : List.copyOf(existing)) {
            view.removeRenderer(r);
        }

        view.addRenderer(new ImageMapRenderer(id, url, plugin.getImageCache()));
        view.setLocked(true);
    }
}
