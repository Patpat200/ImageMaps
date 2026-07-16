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
 * vers les octets d'une image, avec des timeouts stricts et une limite
 * de taille. Toute cette classe est conçue pour ne JAMAIS être appelée
 * sur le thread principal du serveur.
 *
 * Contrairement à une v1 naïve basée sur l'extension du fichier dans
 * l'URL (qui rate les URLs sans extension comme les endpoints Google
 * Images, ou les extensions tronquées), on se base sur le Content-Type
 * HTTP réel renvoyé par le serveur, avec un repli sur une tentative de
 * décodage direct si le Content-Type est absent/ambigu.
 */
public final class ImageResolver {

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024; // 8 Mo max par image
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_OG_IMAGE_DEPTH = 2; // évite les boucles infinies
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36 ImageMapsPlugin/2.0";

    private static final Pattern OG_IMAGE = Pattern.compile(
            "<meta[^>]+(?:property|name)=[\"']og:image(?::secure_url)?[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_SRC = Pattern.compile(
            "<link[^>]+rel=[\"']image_src[\"'][^>]+href=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    private ImageResolver() {}

    public static final class ResolveException extends Exception {
        public ResolveException(String message) { super(message); }
        public ResolveException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Point d'entrée unique : normalise l'URL brute du data.yml, télécharge,
     * et renvoie les octets bruts de l'image (pas encore redimensionnée).
     */
    public static byte[] resolveAndDownload(String rawUrl) throws ResolveException {
        String url = normalizeKnownHost(rawUrl.trim());
        return fetchSmart(url, 0);
    }

    // ------------------------------------------------------------------

    /**
     * Télécharge l'URL et détermine, à partir du Content-Type réel (et non
     * de l'extension du fichier), s'il s'agit déjà d'une image ou d'une
     * page HTML dans laquelle il faut chercher og:image.
     */
    private static byte[] fetchSmart(String url, int depth) throws ResolveException {
        FetchResult result;
        try {
            result = httpGet(url, MAX_IMAGE_BYTES);
        } catch (IOException e) {
            throw new ResolveException("Échec de la requête HTTP vers " + url, e);
        }

        boolean looksLikeImage = result.contentType != null && result.contentType.startsWith("image/");
        boolean looksLikeHtml = result.contentType != null
                && (result.contentType.startsWith("text/html") || result.contentType.startsWith("text/plain"));

        if (looksLikeImage) {
            return result.bytes;
        }

        if (!looksLikeHtml) {
            // Content-Type absent/ambigu (ex: application/octet-stream) :
            // on tente un décodage direct avant de considérer que c'est une page.
            if (isDecodableImage(result.bytes)) {
                return result.bytes;
            }
        }

        // À ce stade on suppose que c'est une page HTML -> on cherche og:image dedans.
        if (depth >= MAX_OG_IMAGE_DEPTH) {
            throw new ResolveException("Trop de redirections via og:image depuis " + url);
        }
        String html = new String(result.bytes, java.nio.charset.StandardCharsets.UTF_8);
        String next = extractOgImage(html);
        if (next == null) {
            throw new ResolveException(
                    "Ni une image (Content-Type: " + result.contentType + "), ni une balise og:image trouvée sur "
                            + url);
        }
        next = normalizeKnownHost(resolveRelative(url, next));
        return fetchSmart(next, depth + 1);
    }

    private static boolean isDecodableImage(byte[] bytes) {
        try {
            return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes)) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static String extractOgImage(String html) {
        Matcher m = OG_IMAGE.matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        m = IMAGE_SRC.matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static String normalizeKnownHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();

            // Dropbox : forcer dl=1 pour obtenir le fichier brut plutôt que la page de preview.
            if (host.contains("dropbox.com")) {
                String cleaned = url.replaceAll("([?&])dl=[^&]*", "$1dl=1");
                if (!cleaned.matches(".*[?&]dl=1(&.*)?$")) {
                    cleaned += (cleaned.contains("?") ? "&" : "?") + "dl=1";
                }
                return cleaned;
            }

            // Google Drive : /file/d/<ID>/view -> lien de téléchargement direct.
            if (host.contains("drive.google.com")) {
                Matcher m = Pattern.compile("/file/d/([a-zA-Z0-9_-]+)").matcher(url);
                if (m.find()) {
                    return "https://drive.google.com/uc?export=download&id=" + m.group(1);
                }
            }

            return url;
        } catch (IllegalArgumentException e) {
            return url; // URL non parseable proprement, on tente telle quelle
        }
    }

    private static String resolveRelative(String baseUrl, String maybeRelative) {
        try {
            return URI.create(baseUrl).resolve(maybeRelative).toString();
        } catch (Exception e) {
            return maybeRelative;
        }
    }

    private record FetchResult(byte[] bytes, String contentType) {}

    private static FetchResult httpGet(String urlStr, int maxBytes) throws IOException {
        String current = urlStr;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URL url = URI.create(current).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setInstanceFollowRedirects(false); // on gère nous-mêmes pour logguer/limiter proprement
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", "image/*,text/html;q=0.8,*/*;q=0.5");

                int status = conn.getResponseCode();

                if (status >= 300 && status < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("Redirection HTTP " + status + " sans en-tête Location depuis " + current);
                    }
                    current = resolveRelative(current, location);
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + status + " pour " + current);
                }

                String contentType = conn.getContentType();
                if (contentType != null) {
                    int semi = contentType.indexOf(';');
                    contentType = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase();
                }

                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 65536));
                    byte[] buf = new byte[8192];
                    int total = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > maxBytes) {
                            throw new IOException("Réponse trop volumineuse (> " + maxBytes + " octets) pour " + current);
                        }
                        out.write(buf, 0, n);
                    }
                    return new FetchResult(out.toByteArray(), contentType);
                }
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("Trop de redirections HTTP (> " + MAX_REDIRECTS + ") depuis " + urlStr);
    }
}
