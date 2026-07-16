package org.saltzus.imagemaps;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;

/**
 * Renderer robuste : ne fait JAMAIS de réseau ou de disque sur le thread
 * principal. render() se contente de lire une image déjà en cache mémoire
 * (quasi gratuit) ; si elle n'est pas encore là, il déclenche un chargement
 * asynchrone en arrière-plan et se contente d'afficher un fond neutre en
 * attendant, au lieu de planter.
 */
public class ImageMapRenderer extends MapRenderer {

    private final int id;
    private final String url;
    private final ImageCache cache;

    public ImageMapRenderer(int id, String url, ImageCache cache) {
        super(true); // contextual=true : Bukkit ne redessine pas pour rien, on gère nous-mêmes
        this.id = id;
        this.url = url;
        this.cache = cache;
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        BufferedImage image = cache.getIfLoaded(id);
        if (image != null) {
            canvas.drawImage(0, 0, image);
            return;
        }

        if (!cache.isInBackoff(id)) {
            cache.loadAsync(id, url);
        }
        // Rien à dessiner pour l'instant : on laisse le canvas tel quel
        // (jamais de canvas.drawImage(x, y, null), qui est ce qui causait
        // le NullPointerException dans l'ancienne version).
    }
}
