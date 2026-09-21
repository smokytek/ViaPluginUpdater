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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

@Plugin(id = "pluginupdater", name = "PluginUpdater", version = "1.4.0-extended",
        description = "Automatically updates SkinsRestorer and Floodgate on Velocity", authors = {"Fil"})
public final class VelocityPluginUpdater {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Properties config = new Properties();
    private final Properties state = new Properties();
    private GitHubReleaseClient client;

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
            if (Boolean.parseBoolean(config.getProperty("show-stack-traces", "false"))) {
                logger.warn("Update of " + target.name() + " failed", exception);
            } else {
                logger.warn("Update of {} failed: {}", target.name(), exception.getMessage());
            }
        }
    }

    private void replaceJar(Path installedJar, byte[] jar) throws IOException {
        Path normalized = installedJar.toAbsolutePath().normalize();
        Path plugins = dataDirectory.toAbsolutePath().normalize().getParent();
        if (plugins == null || !plugins.equals(normalized.getParent())
                || Files.isSymbolicLink(normalized)) {
            throw new IOException("Unsafe plugin path: " + normalized);
        }
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
