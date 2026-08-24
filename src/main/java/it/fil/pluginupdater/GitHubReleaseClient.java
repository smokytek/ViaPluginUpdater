package it.fil.pluginupdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

final class GitHubReleaseClient {
    private static final String API_ROOT = "https://api.github.com/repos/";
    private static final long MAX_JAR_SIZE = 100L * 1024L * 1024L;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String token;

    GitHubReleaseClient(String token) {
        this.token = token == null ? "" : token.trim();
    }

    Optional<ReleaseInfo> latest(TrackedPlugin plugin) throws IOException, InterruptedException {
        return plugin.channel() == UpdateChannel.DEV ? latestDev(plugin) : latestRelease(plugin);
    }

    private Optional<ReleaseInfo> latestRelease(TrackedPlugin plugin) throws IOException, InterruptedException {
        URI uri = URI.create(API_ROOT + plugin.repository() + "/releases/latest");
        HttpRequest.Builder builder = baseRequest(uri).GET();
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub ha risposto con HTTP " + response.statusCode());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String tag = requiredString(root, "tag_name");
        JsonArray assets = root.getAsJsonArray("assets");
        if (assets == null) {
            return Optional.empty();
        }
        for (JsonElement element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = requiredString(asset, "name");
            if (!plugin.assetPattern().matcher(name).matches()) {
                continue;
            }
            long size = asset.has("size") ? asset.get("size").getAsLong() : -1;
            if (size <= 0 || size > MAX_JAR_SIZE) {
                throw new IOException("Dimensione non valida per " + name + ": " + size + " byte");
            }
            URI download = URI.create(requiredString(asset, "browser_download_url"));
            if (!"https".equalsIgnoreCase(download.getScheme()) || !"github.com".equalsIgnoreCase(download.getHost())) {
                throw new IOException("URL di download GitHub non valido");
            }
            String digest = asset.has("digest") && !asset.get("digest").isJsonNull()
                    ? asset.get("digest").getAsString() : "";
            String sha256 = digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : "";
            return Optional.of(new ReleaseInfo(tag, name, download, size, sha256, 0));
        }
        return Optional.empty();
    }

    private Optional<ReleaseInfo> latestDev(TrackedPlugin plugin) throws IOException, InterruptedException {
        URI apiUri = URI.create(plugin.devJobUrl() + "/lastSuccessfulBuild/api/json");
        HttpResponse<String> response = client.send(baseRequest(apiUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("CI ViaVersion ha risposto con HTTP " + response.statusCode());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        int buildNumber = root.get("number").getAsInt();
        URI buildUri = URI.create(requiredString(root, "url"));
        if (!isOfficialCi(buildUri)) {
            throw new IOException("URL build CI non valido");
        }
        JsonArray artifacts = root.getAsJsonArray("artifacts");
        if (artifacts == null) {
            return Optional.empty();
        }
        for (JsonElement element : artifacts) {
            JsonObject artifact = element.getAsJsonObject();
            String name = requiredString(artifact, "fileName");
            if (!plugin.assetPattern().matcher(name).matches()) {
                continue;
            }
            String relativePath = requiredString(artifact, "relativePath");
            if (relativePath.contains("..") || relativePath.startsWith("/") || relativePath.contains("\\")) {
                throw new IOException("Percorso artifact CI non valido");
            }
            URI download = buildUri.resolve("artifact/" + relativePath);
            String prefix = plugin.name() + "-";
            String version = name.substring(prefix.length(), name.length() - ".jar".length());
            return Optional.of(new ReleaseInfo(version, name, download, -1L, "", buildNumber));
        }
        return Optional.empty();
    }

    byte[] download(ReleaseInfo release) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.send(baseRequest(release.downloadUri()).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Download fallito con HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body.length <= 0 || body.length > MAX_JAR_SIZE
                || (release.size() > 0 && body.length != release.size())) {
            throw new IOException("Dimensione del download inattesa: " + body.length + " byte");
        }
        if (!release.sha256().isEmpty() && !release.sha256().equalsIgnoreCase(sha256(body))) {
            throw new IOException("Checksum SHA-256 del download non valido");
        }
        return body;
    }

    private static String sha256(byte[] data) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 non disponibile", impossible);
        }
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        if (!isOfficialSource(uri)) {
            throw new IllegalArgumentException("Host download non consentito: " + uri.getHost());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "PluginUpdater/1.0");
        if (isGitHub(uri)) {
            builder.header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
            if (!token.isEmpty()) {
                builder.header("Authorization", "Bearer " + token);
            }
        }
        return builder;
    }

    private static boolean isOfficialSource(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && (isGitHub(uri) || isOfficialCi(uri));
    }

    private static boolean isGitHub(URI uri) {
        String host = uri.getHost();
        return host != null && (host.equalsIgnoreCase("github.com")
                || host.equalsIgnoreCase("api.github.com"));
    }

    private static boolean isOfficialCi(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && "ci.viaversion.com".equalsIgnoreCase(uri.getHost());
    }

    private static String requiredString(JsonObject object, String key) throws IOException {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            throw new IOException("Risposta GitHub incompleta: manca " + key);
        }
        return object.get(key).getAsString();
    }
}
