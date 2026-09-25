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

final class PaperVelocityClient {
    private static final String API = "https://fill.papermc.io/v3/projects/velocity";
    private static final long MAX_SIZE = 150L * 1024L * 1024L;

    VelocityBuild latestStable() throws IOException {
        JsonObject project = json(URI.create(API)).getAsJsonObject();
        JsonObject groups = project.getAsJsonObject("versions");
        String latestVersion = null;
        for (JsonElement group : groups.entrySet().stream()
                .map(java.util.Map.Entry::getValue).toArray(JsonElement[]::new)) {
            for (JsonElement versionElement : group.getAsJsonArray()) {
                String version = versionElement.getAsString();
                if (version.toUpperCase(Locale.ROOT).contains("SNAPSHOT")) continue;
                if (latestVersion == null || VersionComparator.compare(version, latestVersion) > 0) {
                    latestVersion = version;
                }
            }
        }
        if (latestVersion == null) throw new IOException("No stable Velocity version available");

        JsonArray builds = json(URI.create(API + "/versions/" + latestVersion + "/builds"))
                .getAsJsonArray();
        JsonObject selected = null;
        int selectedId = -1;
        for (JsonElement element : builds) {
            JsonObject build = element.getAsJsonObject();
            if (!"STABLE".equalsIgnoreCase(build.get("channel").getAsString())) continue;
            int id = build.get("id").getAsInt();
            if (id > selectedId) {
                selectedId = id;
                selected = build;
            }
        }
        if (selected == null) throw new IOException("No stable Velocity build available");
        JsonObject download = selected.getAsJsonObject("downloads")
                .getAsJsonObject("server:default");
        return new VelocityBuild(latestVersion, selectedId,
                download.get("name").getAsString(),
                URI.create(download.get("url").getAsString()),
                download.get("size").getAsLong(),
                download.getAsJsonObject("checksums").get("sha256").getAsString());
    }

    byte[] download(VelocityBuild build) throws IOException {
        if (!"https".equalsIgnoreCase(build.downloadUri().getScheme())
                || !"fill-data.papermc.io".equalsIgnoreCase(build.downloadUri().getHost())) {
            throw new IOException("Unexpected Velocity download host");
        }
        byte[] bytes = request(build.downloadUri());
        if (bytes.length != build.size()) throw new IOException("Unexpected Velocity JAR size");
        if (!build.sha256().equalsIgnoreCase(sha256(bytes))) {
            throw new IOException("Velocity JAR SHA-256 verification failed");
        }
        return bytes;
    }

    private static JsonElement json(URI uri) throws IOException {
        return new JsonParser().parse(new String(request(uri), "UTF-8"));
    }

    private static byte[] request(URI uri) throws IOException {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !("fill.papermc.io".equalsIgnoreCase(host)
                || "fill-data.papermc.io".equalsIgnoreCase(host))) {
            throw new IOException("PaperMC API host is not allowed");
        }
        HttpsURLConnection connection = (HttpsURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent",
                "ViaPluginUpdater/1.5.0 (https://github.com/smokytek/ViaPluginUpdater)");
        int status = connection.getResponseCode();
        if (status != 200) {
            connection.disconnect();
            throw new IOException("PaperMC API returned HTTP " + status);
        }
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_SIZE) throw new IOException("PaperMC response exceeds size limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }
}
