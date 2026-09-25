package it.fil.pluginupdater;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

@Plugin(id = "pluginupdater", name = "PluginUpdater", version = "1.5.0-extended",
        description = "Automatically updates SkinsRestorer and Floodgate on Velocity", authors = {"Fil"})
public final class VelocityPluginUpdater {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Properties config = new Properties();
    private final Properties state = new Properties();
    private GitHubReleaseClient client;
    private final PaperVelocityClient velocityClient = new PaperVelocityClient();
    private LocalDate lastRestartAttempt;

    @Inject
    public VelocityPluginUpdater(ProxyServer server, Logger logger,
                                 @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            loadFiles();
        } catch (IOException exception) {
            logger.error("Unable to load PluginUpdater configuration", exception);
            return;
        }
        client = new GitHubReleaseClient(config.getProperty("github-token", ""));
        long hours = Math.max(1L, parseLong(config.getProperty("check-interval-hours"), 6L));
        server.getScheduler().buildTask(this, this::checkAll)
                .delay(Duration.ofSeconds(30))
                .repeat(Duration.ofHours(hours))
                .schedule();
        server.getScheduler().buildTask(this, this::restartAtConfiguredTime)
                .delay(Duration.ofSeconds(45))
                .repeat(Duration.ofMinutes(1))
                .schedule();
        logger.info("PluginUpdater enabled for Velocity: SkinsRestorer and floodgate");
    }

    private void loadFiles() throws IOException {
        Files.createDirectories(dataDirectory);
        Path configFile = dataDirectory.resolve("velocity.properties");
        if (!Files.exists(configFile)) {
            try (InputStream input = getClass().getClassLoader()
                    .getResourceAsStream("velocity.properties")) {
                if (input == null) throw new IOException("Missing velocity.properties resource");
                Files.copy(input, configFile);
            }
        }
        try (InputStream input = Files.newInputStream(configFile)) {
            config.load(input);
        }
        Path stateFile = dataDirectory.resolve("velocity-state.properties");
        if (Files.exists(stateFile)) {
            try (InputStream input = Files.newInputStream(stateFile)) {
                state.load(input);
            }
        }
    }

    private void checkAll() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (TrackedPlugin plugin : trackedPlugins()) checkOne(plugin);
            checkVelocityProxy();
        } finally {
            running.set(false);
        }
    }

    private List<TrackedPlugin> trackedPlugins() {
        UpdateChannel skinsChannel = UpdateChannel.parse(
                config.getProperty("skinsrestorer-channel", "release"));
        return Arrays.asList(
                new TrackedPlugin("skinsrestorer", "SkinsRestorer/SkinsRestorer",
                        Pattern.compile("^SkinsRestorer\\.jar$"), skinsChannel,
                        "https://ci.codemc.io/job/SkinsRestorer/job/SkinsRestorer", ""),
                new TrackedPlugin("floodgate", "GeyserMC/Floodgate",
                        Pattern.compile("^floodgate-velocity\\.jar$"), UpdateChannel.DEV,
                        "", "", "velocity")
        );
    }

    private void checkOne(TrackedPlugin target) {
        Optional<PluginContainer> installed = server.getPluginManager().getPlugin(target.name());
        if (!installed.isPresent()) return;
        try {
            String current = installed.get().getDescription().getVersion().orElse("0");
            Optional<ReleaseInfo> available = client.latest(target);
            if (!available.isPresent()) {
                logger.warn("No compatible {} artifact found", target.name());
                return;
            }
            ReleaseInfo release = available.get();
            boolean currentBuild = target.channel() == UpdateChannel.DEV
                    && Integer.toString(release.buildNumber()).equals(
                    state.getProperty(target.name() + ".build"));
            boolean currentRelease = target.channel() == UpdateChannel.RELEASE
                    && VersionComparator.compare(release.version(), current) <= 0;
            if (currentBuild || currentRelease) return;
            Path source = installed.get().getDescription().getSource()
                    .orElseThrow(() -> new IOException("Plugin source path unavailable"));
            byte[] jar = client.download(release);
            validateVelocityJar(jar, target.name());
            replaceJar(source, jar);
            if (target.channel() == UpdateChannel.DEV) {
                state.setProperty(target.name() + ".build", Integer.toString(release.buildNumber()));
                saveState();
            }
            logger.info("{} {} downloaded; restart Velocity to apply it", target.name(),
                    release.displayVersion());
        } catch (Exception exception) {
            logFailure("Update of " + target.name(), exception);
        }
    }

    private void checkVelocityProxy() {
        if (!Boolean.parseBoolean(config.getProperty("velocity-update-enabled", "true"))) return;
        try {
            VelocityBuild latest = velocityClient.latestStable();
            String current = server.getVersion().getVersion();
            if (VersionComparator.compare(current, latest.version()) >= 0) return;
            if (latest.version().equals(state.getProperty("velocity.version"))
                    && Integer.toString(latest.build()).equals(state.getProperty("velocity.build"))
                    && "true".equalsIgnoreCase(
                    state.getProperty("velocity.update-pending", "false"))) return;
            byte[] jar = velocityClient.download(latest);
            validateVelocityServerJar(jar);
            replaceFile(velocityJarPath(), jar);
            state.setProperty("velocity.version", latest.version());
            state.setProperty("velocity.build", Integer.toString(latest.build()));
            state.setProperty("velocity.update-pending", "true");
            saveState();
            logger.info("Velocity {} build {} downloaded; Pterodactyl restart is scheduled for 03:00",
                    latest.version(), latest.build());
        } catch (Exception exception) {
            logFailure("Velocity update", exception);
        }
    }

    private void replaceJar(Path installedJar, byte[] jar) throws IOException {
        Path normalized = installedJar.toAbsolutePath().normalize();
        Path plugins = dataDirectory.toAbsolutePath().normalize().getParent();
        if (plugins == null || !plugins.equals(normalized.getParent())
                || Files.isSymbolicLink(normalized)) {
            throw new IOException("Unsafe plugin path: " + normalized);
        }
        replaceFile(normalized, jar);
    }

    private void replaceFile(Path destination, byte[] jar) throws IOException {
        Path normalized = destination.toAbsolutePath().normalize();
        Path temporary = dataDirectory.resolve(normalized.getFileName() + ".download");
        Files.write(temporary, jar, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path velocityJarPath() throws IOException {
        Path plugins = dataDirectory.toAbsolutePath().normalize().getParent();
        Path proxyRoot = plugins == null ? null : plugins.getParent();
        String fileName = config.getProperty("velocity-jar", "velocity.jar").trim();
        if (proxyRoot == null || !fileName.matches("[A-Za-z0-9_.-]+\\.jar")) {
            throw new IOException("velocity-jar must be a JAR filename in the proxy root directory");
        }
        Path path = proxyRoot.resolve(fileName).normalize();
        if (!proxyRoot.equals(path.getParent()) || Files.isSymbolicLink(path)) {
            throw new IOException("Unsafe Velocity JAR path");
        }
        return path;
    }

    private static void validateVelocityServerJar(byte[] jarBytes) throws IOException {
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if ("com/velocitypowered/proxy/Velocity.class".equals(entry.getName())) return;
            }
        }
        throw new IOException("Downloaded file is not a Velocity proxy JAR");
    }

    private static void validateVelocityJar(byte[] jarBytes, String expectedId) throws IOException {
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (!"velocity-plugin.json".equals(entry.getName())) continue;
                JsonObject description = new JsonParser().parse(
                        new InputStreamReader(jar, StandardCharsets.UTF_8)).getAsJsonObject();
                String id = description.get("id").getAsString();
                if (!expectedId.equalsIgnoreCase(id)) {
                    throw new IOException("JAR declares Velocity plugin " + id
                            + " instead of " + expectedId);
                }
                return;
            }
        }
        throw new IOException("velocity-plugin.json is missing from downloaded JAR");
    }

    private void restartAtConfiguredTime() {
        if (!"true".equalsIgnoreCase(state.getProperty("velocity.update-pending", "false"))) return;
        if (!Boolean.parseBoolean(config.getProperty("pterodactyl-restart-enabled", "false"))) return;
        try {
            ZoneId zone = ZoneId.of(config.getProperty("restart-zone", "Europe/Rome"));
            ZonedDateTime now = ZonedDateTime.now(zone);
            int hour = (int) parseLong(config.getProperty("restart-hour"), 3L);
            if (now.getHour() != hour || lastRestartAttempt != null
                    && lastRestartAttempt.equals(now.toLocalDate())) return;
            lastRestartAttempt = now.toLocalDate();
            sendPterodactylRestart();
            state.setProperty("velocity.update-pending", "false");
            saveState();
            logger.info("Pterodactyl accepted the scheduled Velocity restart");
        } catch (Exception exception) {
            logFailure("Scheduled Pterodactyl restart", exception);
        }
    }

    private void sendPterodactylRestart() throws IOException {
        String base = config.getProperty("pterodactyl-panel-url", "").replaceAll("/+$", "");
        String serverId = config.getProperty("pterodactyl-server-id", "").trim();
        String token = config.getProperty("pterodactyl-client-api-token", "").trim();
        if (!serverId.matches("[A-Za-z0-9_-]{4,64}") || token.isEmpty()) {
            throw new IOException("Pterodactyl server ID or Client API token is missing");
        }
        URI baseUri = URI.create(base);
        boolean allowHttp = Boolean.parseBoolean(config.getProperty("pterodactyl-allow-http", "false"));
        if (!("https".equalsIgnoreCase(baseUri.getScheme())
                || allowHttp && "http".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getHost() == null || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new IOException("Pterodactyl panel URL must use HTTPS");
        }
        URL endpoint = new URL(base + "/api/client/servers/" + serverId + "/power");
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "Application/vnd.pterodactyl.v1+json");
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] body = "{\"signal\":\"restart\"}".getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (java.io.OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        int status = connection.getResponseCode();
        connection.disconnect();
        if (status != 204) throw new IOException("Pterodactyl returned HTTP " + status);
    }

    private void logFailure(String operation, Exception exception) {
        if (Boolean.parseBoolean(config.getProperty("show-stack-traces", "false"))) {
            logger.warn(operation + " failed", exception);
        } else {
            logger.warn("{} failed: {}", operation, exception.getMessage());
        }
    }

    private void saveState() throws IOException {
        try (java.io.OutputStream output = Files.newOutputStream(
                dataDirectory.resolve("velocity-state.properties"),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            state.store(output, "PluginUpdater Velocity state");
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
