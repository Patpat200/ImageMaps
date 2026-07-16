package org.saltzus.imagemaps;

import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Gère le cycle de vie des images : cache mémoire, cache disque (pour ne
 * pas re-télécharger à chaque redémarrage du serveur), et dédoublonnage
 * des requêtes de chargement concurrentes pour un même identifiant.
 *
 * Toute I/O (réseau + disque) est faite sur un pool de threads dédié,
 * jamais sur le thread principal du serveur.
 */
public final class ImageCache {

    private static final int MAP_SIZE = 128;
    private static final long RETRY_BACKOFF_MS = TimeUnit.SECONDS.toMillis(60);

    private final JavaPlugin plugin;
    private final File cacheDir;
    private final Executor ioExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ImageMaps-IO");
        t.setDaemon(true);
        return t;
    });

    private final Map<Integer, BufferedImage> memoryCache = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<BufferedImage>> inFlight = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastFailureMillis = new ConcurrentHashMap<>();

    public ImageCache(JavaPlugin plugin) {
        this.plugin = plugin;
        this.cacheDir = new File(plugin.getDataFolder(), "cache");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            plugin.getLogger().warning("Impossible de créer le dossier de cache : " + cacheDir);
        }
    }

    /** Retourne l'image en cache mémoire si elle est déjà chargée, sinon null (non bloquant). */
    public BufferedImage getIfLoaded(int id) {
        return memoryCache.get(id);
    }

    /**
     * True si on a échoué récemment sur cet id et qu'on est encore dans la
     * fenêtre de backoff (évite de spammer le réseau/les logs).
     */
    public boolean isInBackoff(int id) {
        Long last = lastFailureMillis.get(id);
        return last != null && (System.currentTimeMillis() - last) < RETRY_BACKOFF_MS;
    }

    /**
     * Déclenche (ou rejoint) le chargement asynchrone de l'image pour cet id.
     * Ne fait rien si un chargement est déjà en cours pour cet id.
     */
    public void loadAsync(int id, String rawUrl) {
        inFlight.computeIfAbsent(id, k -> {
            CompletableFuture<BufferedImage> future = CompletableFuture.supplyAsync(
                    () -> loadBlocking(id, rawUrl), ioExecutor);
            future.whenComplete((image, throwable) -> {
                inFlight.remove(id);
                if (image != null) {
                    memoryCache.put(id, image);
                    lastFailureMillis.remove(id);
                } else {
                    lastFailureMillis.put(id, System.currentTimeMillis());
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "[ImageMaps] Échec de chargement de l'image #" + id + " (" + rawUrl + ")",
                                throwable);
                    }
                }
            });
            return future;
        });
    }

    public void clearMemory(int id) {
        memoryCache.remove(id);
        lastFailureMillis.remove(id);
    }

    public void clearDisk(int id) {
        File f = diskFile(id);
        if (f.exists() && !f.delete()) {
            plugin.getLogger().warning("Impossible de supprimer le cache disque pour #" + id);
        }
    }

    public void clearAll() {
        memoryCache.clear();
        lastFailureMillis.clear();
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    // ------------------------------------------------------------------
    // Exécuté uniquement sur le pool ioExecutor, jamais sur le thread principal.

    private BufferedImage loadBlocking(int id, String rawUrl) {
        // 1) Cache disque : pas de réseau nécessaire si on l'a déjà.
        File diskFile = diskFile(id);
        if (diskFile.exists()) {
            try {
                BufferedImage cached = ImageIO.read(diskFile);
                if (cached != null) {
                    return cached;
                }
                plugin.getLogger().warning("[ImageMaps] Cache disque corrompu pour #" + id + ", re-téléchargement.");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "[ImageMaps] Lecture du cache disque échouée pour #" + id, e);
            }
        }

        // 2) Résolution + téléchargement réseau.
        try {
            String directUrl = ImageResolver.resolveDirectUrl(rawUrl);
            byte[] bytes = ImageResolver.download(directUrl);
            BufferedImage raw = ImageIO.read(new ByteArrayInputStream(bytes));
            if (raw == null) {
                throw new IOException("Les données téléchargées ne sont pas une image valide (" + directUrl + ")");
            }
            BufferedImage fitted = fitToMap(raw);
            saveToDisk(diskFile, fitted);
            return fitted;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "[ImageMaps] Impossible de résoudre/télécharger l'image #" + id + " (" + rawUrl + ") : "
                            + e.getMessage(), plugin.getLogger().isLoggable(Level.FINE) ? e : null);
            return null;
        }
    }

    /** Recadre/redimensionne (mode "cover") l'image source en 128x128 pour coller à une carte. */
    private static BufferedImage fitToMap(BufferedImage src) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        double scale = Math.max((double) MAP_SIZE / sw, (double) MAP_SIZE / sh);
        int scaledW = (int) Math.ceil(sw * scale);
        int scaledH = (int) Math.ceil(sh * scale);
        int cropX = (scaledW - MAP_SIZE) / 2;
        int cropY = (scaledH - MAP_SIZE) / 2;

        BufferedImage result = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, -cropX, -cropY, scaledW, scaledH, null);
        } finally {
            g.dispose();
        }
        return result;
    }

    private void saveToDisk(File file, BufferedImage image) {
        try {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            ImageIO.write(image, "png", tmp);
            Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[ImageMaps] Écriture du cache disque échouée pour " + file, e);
        }
    }

    private File diskFile(int id) {
        return new File(cacheDir, id + ".png");
    }
}
