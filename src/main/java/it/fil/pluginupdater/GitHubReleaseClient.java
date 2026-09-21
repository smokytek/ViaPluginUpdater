package it.fil.pluginupdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;

final class GitHubReleaseClient {
    private static final String API_ROOT = "https://api.github.com/repos/";
    private static final long MAX_JAR_SIZE = 100L * 1024L * 1024L;
    private final String token;

    GitHubReleaseClient(String token) {
        this.token = token == null ? "" : token.trim();
    }

    Optional<ReleaseInfo> latest(TrackedPlugin plugin) throws IOException {
        if (plugin.channel() == UpdateChannel.DEV && !plugin.devReleaseTag().isEmpty()) {
            return taggedDevRelease(plugin);
        }
        return plugin.channel() == UpdateChannel.DEV ? latestDev(plugin) : latestRelease(plugin);
    }

    private Optional<ReleaseInfo> latestRelease(TrackedPlugin plugin) throws IOException {
        URI uri = URI.create(API_ROOT + plugin.repository() + "/releases?per_page=20");
        JsonArray releases = requestJsonElement(uri).getAsJsonArray();
        for (JsonElement element : releases) {
            JsonObject release = element.getAsJsonObject();
            String tag = requiredString(release, "tag_name");
            boolean draft = release.has("draft") && release.get("draft").getAsBoolean();
            boolean prerelease = release.has("prerelease") && release.get("prerelease").getAsBoolean();
            if (!draft && !prerelease && !tag.equals(plugin.devReleaseTag())) {
                return releaseAsset(plugin, release, false);
            }
        }
        return Optional.empty();
    }

    private Optional<ReleaseInfo> taggedDevRelease(TrackedPlugin plugin) throws IOException {
        URI uri = URI.create(API_ROOT + plugin.repository() + "/releases/tags/" + plugin.devReleaseTag());
        return releaseAsset(plugin, requestJsonElement(uri).getAsJsonObject(), true);
    }

    private Optional<ReleaseInfo> releaseAsset(TrackedPlugin plugin, JsonObject root,
                                               boolean development) throws IOException {
        String tag = requiredString(root, "tag_name");
        JsonArray assets = root.getAsJsonArray("assets");
        if (assets == null) return Optional.empty();
        for (JsonElement element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = requiredString(asset, "name");
            if (!plugin.assetPattern().matcher(name).matches()) continue;
            long size = asset.has("size") ? asset.get("size").getAsLong() : -1L;
            if (size <= 0 || size > MAX_JAR_SIZE) {
                throw new IOException("Dimensione non valida per " + name + ": " + size + " byte");
            }
            URI download = URI.create(requiredString(asset, "browser_download_url"));
            if (!isGitHubDownload(download)) throw new IOException("URL GitHub non valido");
            String digest = asset.has("digest") && !asset.get("digest").isJsonNull()
                    ? asset.get("digest").getAsString() : "";
            String checksum = digest.startsWith("sha256:") ? digest.substring(7) : "";
            int build = development && asset.has("id") ? asset.get("id").getAsInt() : 0;
            return Optional.of(new ReleaseInfo(tag, name, download, size, checksum, build));
        }
        return Optional.empty();
    }

    private Optional<ReleaseInfo> latestDev(TrackedPlugin plugin) throws IOException {
        URI api = URI.create(plugin.devJobUrl() + "/lastSuccessfulBuild/api/json");
        JsonObject root = requestJson(api);
        int buildNumber = root.get("number").getAsInt();
        URI buildUri = URI.create(requiredString(root, "url"));
        if (!isOfficialCi(buildUri)) throw new IOException("URL build CI non valido");
        JsonArray artifacts = root.getAsJsonArray("artifacts");
        if (artifacts == null) return Optional.empty();
        for (JsonElement element : artifacts) {
            JsonObject artifact = element.getAsJsonObject();
            String name = requiredString(artifact, "fileName");
            if (!plugin.assetPattern().matcher(name).matches()) continue;
            String path = requiredString(artifact, "relativePath");
            if (path.contains("..") || path.startsWith("/") || path.contains("\\")) {
                throw new IOException("Percorso artifact CI non valido");
            }
            URI download = buildUri.resolve("artifact/" + path);
            String version = versionFromAsset(name);
            return Optional.of(new ReleaseInfo(version, name, download, -1L, "", buildNumber));
        }
        return Optional.empty();
    }

    byte[] download(ReleaseInfo release) throws IOException {
        if (!isOfficialSource(release.downloadUri())) throw new IOException("Host download non consentito");
        byte[] body = requestBytes(release.downloadUri(), false);
        if (body.length == 0 || body.length > MAX_JAR_SIZE
                || (release.size() > 0 && body.length != release.size())) {
            throw new IOException("Dimensione del download inattesa: " + body.length + " byte");
        }
        if (!release.sha256().isEmpty() && !release.sha256().equalsIgnoreCase(sha256(body))) {
            throw new IOException("Checksum SHA-256 del download non valido");
        }
        return body;
    }

    private JsonElement requestJsonElement(URI uri) throws IOException {
        byte[] bytes = requestBytes(uri, isGitHubApi(uri));
        return new JsonParser().parse(new String(bytes, "UTF-8"));
    }

    private JsonObject requestJson(URI uri) throws IOException {
        return requestJsonElement(uri).getAsJsonObject();
    }

    private static String versionFromAsset(String name) throws IOException {
        String withoutJar = name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
        for (int i = 0; i < withoutJar.length(); i++) {
            if (Character.isDigit(withoutJar.charAt(i))) return withoutJar.substring(i);
        }
        // Some official CI jobs publish a stable artifact name (for example
        // SkinsRestorer.jar). The Jenkins build number still uniquely identifies
        // the development build and is used to detect subsequent updates.
        return "dev";
    }

    private byte[] requestBytes(URI uri, boolean apiRequest) throws IOException {
        if (!isOfficialSource(uri)) throw new IOException("Host non consentito: " + uri.getHost());
        HttpsURLConnection connection = (HttpsURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "ViaPluginUpdater/1.1");
        if (apiRequest) {
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        int status = connection.getResponseCode();
        if (status != 200) {
            connection.disconnect();
            throw new IOException("Il servizio aggiornamenti ha risposto con HTTP " + status);
        }
        InputStream input = connection.getInputStream();
        try {
            return readLimited(input);
        } finally {
            input.close();
            connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_JAR_SIZE) throw new IOException("Download oltre il limite consentito");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] data) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 non disponibile", impossible);
        }
    }

    private static boolean isOfficialSource(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && (isGitHubApi(uri) || isGitHubDownload(uri) || isOfficialCi(uri));
    }

    private static boolean isGitHubApi(URI uri) {
        return "api.github.com".equalsIgnoreCase(uri.getHost());
    }

    private static boolean isGitHubDownload(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && "github.com".equalsIgnoreCase(uri.getHost());
    }

    private static boolean isOfficialCi(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && ("ci.viaversion.com".equalsIgnoreCase(uri.getHost())
                || "ci.codemc.io".equalsIgnoreCase(uri.getHost()));
    }

    private static String requiredString(JsonObject object, String key) throws IOException {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            throw new IOException("Risposta incompleta: manca " + key);
        }
        return object.get(key).getAsString();
    }
}
