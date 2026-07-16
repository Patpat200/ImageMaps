package org.saltzus.imagemaps;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Résout une URL "quelconque" (page web, lien de partage, lien direct...)
 * vers une URL d'image directement téléchargeable, puis télécharge les
 * octets de l'image avec des timeouts stricts.
 *
 * Toute cette classe est conçue pour ne JAMAIS être appelée sur le thread
 * principal du serveur : chaque appel réseau a un timeout court et une
 * limite de taille, pour ne jamais bloquer indéfiniment.
 */
public final class ImageResolver {

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;
    private static final int MAX_PAGE_BYTES = 128 * 1024;      // 128 Ko pour scanner le HTML
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024; // 8 Mo max par image
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36 ImageMapsPlugin/2.0";

    private static final Pattern OG_IMAGE = Pattern.compile(
            "<meta[^>]+(?:property|name)=[\"']og:image(?::secure_url)?[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_SRC = Pattern.compile(
            "<link[^>]+rel=[\"']image_src[\"'][^>]+href=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_EXT = Pattern.compile(
            "\\.(png|jpe?g|gif|webp|bmp)(\\?.*)?$", Pattern.CASE_INSENSITIVE);

    private ImageResolver() {}

    public static final class ResolveException extends Exception {
        public ResolveException(String message) { super(message); }
        public ResolveException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Étape 1 : normalise/résout l'URL brute du data.yml vers une URL
     * pointant directement sur les octets de l'image.
     */
    public static String resolveDirectUrl(String rawUrl) throws ResolveException {
        String url = rawUrl.trim();
        url = normalizeKnownHost(url);

        if (isLikelyDirectImage(url)) {
            return url;
        }

        // Sinon, c'est probablement une page HTML (Pinterest, ImgBB, Imgur, X...)
        // -> on va chercher la balise og:image / image_src dedans.
        String scraped = scrapeOgImage(url);
        if (scraped == null) {
            throw new ResolveException("Impossible de trouver une image directe sur la page : " + url);
        }
        scraped = normalizeKnownHost(scraped);
        return scraped;
    }

    /** Étape 2 : télécharge les octets bruts de l'image finale. */
    public static byte[] download(String directUrl) throws ResolveException {
        try {
            return httpGetBytes(directUrl, MAX_IMAGE_BYTES);
        } catch (IOException e) {
            throw new ResolveException("Échec du téléchargement de l'image : " + directUrl, e);
        }
    }

    // ------------------------------------------------------------------

    private static boolean isLikelyDirectImage(String url) {
        return DIRECT_EXT.matcher(url).find();
    }

    private static String normalizeKnownHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();

            // Dropbox : forcer dl=1 pour obtenir le fichier brut plutôt que la page de preview
            if (host.contains("dropbox.com")) {
                String cleaned = url.replaceAll("([?&])dl=(0|1)?(&|$)", "$1dl=1$3");
                if (!cleaned.matches(".*[?&]dl=1(&.*)?$")) {
                    cleaned += (cleaned.contains("?") ? "&" : "?") + "dl=1";
                }
                // Corrige un éventuel paramètre malformé du style "dl=1T", "dl=", etc.
                cleaned = cleaned.replaceAll("dl=1[^&]*", "dl=1");
                return cleaned;
            }

            // Google Drive : /file/d/<ID>/view -> lien de téléchargement direct
            if (host.contains("drive.google.com")) {
                Matcher m = Pattern.compile("/file/d/([a-zA-Z0-9_-]+)").matcher(url);
                if (m.find()) {
                    return "https://drive.google.com/uc?export=download&id=" + m.group(1);
                }
            }

            // Imgur : lien d'album (imgur.com/a/xxx) -> on laissera le scraper og:image
            // faire le travail ; i.imgur.com/xxx est déjà direct.

            return url;
        } catch (IllegalArgumentException e) {
            return url; // URL non parseable proprement, on tente telle quelle
        }
    }

    private static String scrapeOgImage(String pageUrl) throws ResolveException {
        byte[] html;
        try {
            html = httpGetBytes(pageUrl, MAX_PAGE_BYTES);
        } catch (IOException e) {
            throw new ResolveException("Impossible de charger la page : " + pageUrl, e);
        }
        String content = new String(html, java.nio.charset.StandardCharsets.UTF_8);

        Matcher m = OG_IMAGE.matcher(content);
        if (m.find()) {
            return resolveRelative(pageUrl, m.group(1));
        }
        m = IMAGE_SRC.matcher(content);
        if (m.find()) {
            return resolveRelative(pageUrl, m.group(1));
        }
        return null;
    }

    private static String resolveRelative(String baseUrl, String maybeRelative) {
        try {
            return URI.create(baseUrl).resolve(maybeRelative).toString();
        } catch (Exception e) {
            return maybeRelative;
        }
    }

    private static byte[] httpGetBytes(String urlStr, int maxBytes) throws IOException {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");

            int status = conn.getResponseCode();
            if (status >= 300 && status < 400) {
                // Redirection manuelle si besoin (certains hébergeurs changent de protocole https<->http,
                // ce que HttpURLConnection ne suit pas automatiquement).
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) {
                    throw new IOException("Redirection sans en-tête Location depuis " + urlStr);
                }
                return httpGetBytes(resolveRelative(urlStr, location), maxBytes);
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " pour " + urlStr);
            }

            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 65536));
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) {
                        throw new IOException("Réponse trop volumineuse (> " + maxBytes + " octets) pour " + urlStr);
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }
}
