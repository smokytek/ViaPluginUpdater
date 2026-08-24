package it.fil.pluginupdater;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class PluginUpdaterPlugin extends JavaPlugin implements TabExecutor {
    private static final String PREFIX = ChatColor.DARK_AQUA + "[PluginUpdater] " + ChatColor.RESET;

    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, String> latestVersions = new HashMap<>();
    private final Map<String, String> stagedVersions = new HashMap<>();
    private final Map<String, Integer> downloadedDevBuilds = new HashMap<>();
    private List<TrackedPlugin> tracked = List.of();
    private GitHubReleaseClient client;
    private BukkitTask scheduledTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadUpdaterData();
        if (getCommand("pluginupdater") != null) {
            getCommand("pluginupdater").setExecutor(this);
            getCommand("pluginupdater").setTabCompleter(this);
        }
        reloadUpdater();
        scheduleOldJarCleanup();
        getLogger().info("PluginUpdater attivo. Plugin monitorati: " + tracked.size());
    }

    @Override
    public void onDisable() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
        }
    }

    private void reloadUpdater() {
        reloadConfig();
        tracked = loadTrackedPlugins();
        client = new GitHubReleaseClient(getConfig().getString("github-token", ""));
        if (scheduledTask != null) {
            scheduledTask.cancel();
        }
        long hours = Math.max(1L, getConfig().getLong("check-interval-hours", 6L));
        long period = hours * 60L * 60L * 20L;
        long delay = getConfig().getBoolean("check-on-startup", true) ? 20L * 30L : period;
        scheduledTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                () -> checkAll(null, getConfig().getBoolean("download-updates", true)), delay, period);
    }

    private List<TrackedPlugin> loadTrackedPlugins() {
        ConfigurationSection root = getConfig().getConfigurationSection("plugins");
        if (root == null) {
            getLogger().warning("Nessun plugin configurato nella sezione plugins.");
            return List.of();
        }
        List<TrackedPlugin> result = new ArrayList<>();
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null || !section.getBoolean("enabled", true)) {
                continue;
            }
            String repository = section.getString("repository", "").trim();
            String assetRegex = section.getString("asset-regex", "").trim();
            UpdateChannel channel = UpdateChannel.parse(section.getString("channel", "release"));
            String devJobUrl = section.getString("dev-job-url", defaultDevJobUrl(name)).replaceAll("/+$", "");
            if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
                getLogger().warning("Repository non valido per " + name + ": " + repository);
                continue;
            }
            if (channel == UpdateChannel.DEV
                    && !devJobUrl.matches("https://ci\\.viaversion\\.com/[A-Za-z0-9_./-]+")) {
                getLogger().warning("dev-job-url non valido per " + name + ": " + devJobUrl);
                continue;
            }
            try {
                result.add(new TrackedPlugin(name, repository, Pattern.compile(assetRegex), channel, devJobUrl));
            } catch (PatternSyntaxException exception) {
                getLogger().warning("asset-regex non valida per " + name + ": " + exception.getMessage());
            }
        }
        return List.copyOf(result);
    }

    private static String defaultDevJobUrl(String name) {
        return "ViaVersion".equals(name)
                ? "https://ci.viaversion.com/job/ViaVersion"
                : "https://ci.viaversion.com/view/" + name + "/job/" + name;
    }

    private void scheduleOldJarCleanup() {
        if (!getConfig().getBoolean("delete-old-jars", true)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Map<String, String> loadedVersions = new HashMap<>();
            for (TrackedPlugin target : tracked) {
                Plugin loaded = Bukkit.getPluginManager().getPlugin(target.name());
                if (loaded != null) {
                    loadedVersions.put(target.name(), loaded.getPluginMeta().getVersion());
                }
            }
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> cleanupOldJars(loadedVersions));
        }, 20L * 5L);
    }

    private void cleanupOldJars(Map<String, String> loadedVersions) {
        Path pluginsFolder = getDataFolder().toPath().getParent().toAbsolutePath().normalize();
        try (var files = Files.list(pluginsFolder)) {
            files.filter(path -> Files.isRegularFile(path) && !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .forEach(path -> inspectAndDeleteOldJar(pluginsFolder, path, loadedVersions));
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "Impossibile controllare i vecchi JAR", exception);
        }
    }

    private void inspectAndDeleteOldJar(Path pluginsFolder, Path candidate,
                                        Map<String, String> loadedVersions) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!pluginsFolder.equals(normalized.getParent())) {
            return;
        }
        String name;
        String jarVersion;
        try (JarFile jar = new JarFile(normalized.toFile())) {
            JarEntry pluginYml = jar.getJarEntry("plugin.yml");
            if (pluginYml == null) {
                return;
            }
            YamlConfiguration description = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(jar.getInputStream(pluginYml), StandardCharsets.UTF_8));
            name = description.getString("name", "");
            jarVersion = description.getString("version", "");
        } catch (IOException | RuntimeException exception) {
            getLogger().log(Level.WARNING, "Impossibile esaminare " + normalized.getFileName(), exception);
            return;
        }
        String loadedVersion = loadedVersions.get(name);
        if (loadedVersion == null || VersionComparator.compare(jarVersion, loadedVersion) >= 0) {
            return;
        }
        try {
            Files.delete(normalized);
            getLogger().info("Eliminato vecchio JAR " + normalized.getFileName()
                    + " (" + name + " " + jarVersion + ", attiva " + loadedVersion + ").");
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "Impossibile eliminare " + normalized.getFileName(), exception);
        }
    }

    private void checkAll(CommandSender sender, boolean download) {
        if (!running.compareAndSet(false, true)) {
            tell(sender, ChatColor.YELLOW + "Un controllo è già in corso.");
            return;
        }
        tell(sender, ChatColor.GRAY + "Controllo delle release in corso...");
        try {
            for (TrackedPlugin target : tracked) {
                checkOne(target, sender, download);
            }
            tell(sender, ChatColor.GREEN + "Controllo completato.");
        } finally {
            running.set(false);
        }
    }

    private void checkOne(TrackedPlugin target, CommandSender sender, boolean download) {
        try {
            Plugin installedPlugin = Bukkit.getPluginManager().getPlugin(target.name());
            if (installedPlugin == null) {
                tell(sender, ChatColor.YELLOW + target.name() + ": non installato, ignorato.");
                return;
            }
            String installed = installedPlugin.getPluginMeta().getVersion();
            Optional<ReleaseInfo> optionalRelease = client.latest(target);
            if (optionalRelease.isEmpty()) {
                tell(sender, ChatColor.YELLOW + target.name() + ": nessun JAR compatibile nel canale "
                        + target.channel().name().toLowerCase(Locale.ROOT) + ".");
                return;
            }
            ReleaseInfo release = optionalRelease.get();
            synchronized (latestVersions) {
                latestVersions.put(target.name(), release.displayVersion());
            }
            boolean upToDate = target.channel() == UpdateChannel.DEV
                    ? downloadedDevBuild(target.name()) == release.buildNumber()
                        && VersionComparator.compare(release.version(), installed) == 0
                    : VersionComparator.compare(release.version(), installed) <= 0
                        && !isDevelopmentVersion(installed);
            if (upToDate) {
                tell(sender, ChatColor.GREEN + target.name() + " è aggiornato (" + installed + ").");
                return;
            }
            tell(sender, ChatColor.GOLD + target.name() + ": disponibile " + release.displayVersion()
                    + " (installata " + installed + ").");
            if (download && !release.displayVersion().equals(stagedVersion(target.name()))) {
                stage(target, release);
                tell(sender, ChatColor.AQUA + target.name() + " " + release.displayVersion()
                        + " pronto: verrà installato al prossimo riavvio.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logFailure(target, sender, "controllo interrotto", exception);
        } catch (Exception exception) {
            logFailure(target, sender, exception.getMessage(), exception);
        }
    }

    private void stage(TrackedPlugin target, ReleaseInfo release) throws IOException, InterruptedException {
        byte[] jar = client.download(release);
        validateJar(jar, target.name(), release.version());

        Path updateFolder = getDataFolder().toPath().getParent().resolve("update");
        Files.createDirectories(updateFolder);
        Path temp = getDataFolder().toPath().resolve(target.name() + ".download");
        Files.createDirectories(getDataFolder().toPath());
        Files.write(temp, jar, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Path destination = updateFolder.resolve(target.name() + ".jar");
        try {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        synchronized (stagedVersions) {
            stagedVersions.put(target.name(), release.displayVersion());
        }
        if (release.buildNumber() > 0) {
            synchronized (downloadedDevBuilds) {
                downloadedDevBuilds.put(target.name(), release.buildNumber());
            }
            saveUpdaterData();
        }
    }

    private int downloadedDevBuild(String name) {
        synchronized (downloadedDevBuilds) {
            return downloadedDevBuilds.getOrDefault(name, -1);
        }
    }

    private static boolean isDevelopmentVersion(String version) {
        String value = version.toLowerCase(Locale.ROOT);
        return value.contains("snapshot") || value.contains("-dev") || value.contains("+dev");
    }

    private void loadUpdaterData() {
        Path path = getDataFolder().toPath().resolve("data.yml");
        if (!Files.isRegularFile(path)) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(path.toFile());
        ConfigurationSection builds = data.getConfigurationSection("downloaded-dev-builds");
        if (builds == null) {
            return;
        }
        synchronized (downloadedDevBuilds) {
            for (String name : builds.getKeys(false)) {
                downloadedDevBuilds.put(name, builds.getInt(name, -1));
            }
        }
    }

    private void saveUpdaterData() throws IOException {
        YamlConfiguration data = new YamlConfiguration();
        synchronized (downloadedDevBuilds) {
            downloadedDevBuilds.forEach((name, build) ->
                    data.set("downloaded-dev-builds." + name, build));
        }
        Files.createDirectories(getDataFolder().toPath());
        data.save(getDataFolder().toPath().resolve("data.yml").toFile());
    }

    private void validateJar(byte[] bytes, String expectedName, String expectedVersion) throws IOException {
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(bytes))) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (!"plugin.yml".equals(entry.getName())) {
                    continue;
                }
                YamlConfiguration description = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(jar, StandardCharsets.UTF_8));
                String name = description.getString("name", "");
                String version = description.getString("version", "");
                if (!expectedName.equals(name)) {
                    throw new IOException("Il JAR dichiara il plugin " + name + " invece di " + expectedName);
                }
                if (VersionComparator.compare(version, expectedVersion) != 0) {
                    throw new IOException("Versione JAR " + version + " diversa dalla release " + expectedVersion);
                }
                return;
            }
        }
        throw new IOException("plugin.yml non trovato nel JAR scaricato");
    }

    private String stagedVersion(String name) {
        synchronized (stagedVersions) {
            return stagedVersions.get(name);
        }
    }

    private void logFailure(TrackedPlugin target, CommandSender sender, String message, Exception exception) {
        String safeMessage = message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
        getLogger().log(Level.WARNING, "Aggiornamento di " + target.name() + " fallito: " + safeMessage, exception);
        tell(sender, ChatColor.RED + target.name() + ": " + safeMessage);
    }

    private void tell(CommandSender sender, String message) {
        if (sender != null) {
            Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(PREFIX + message));
        } else {
            getLogger().info(ChatColor.stripColor(message));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            showStatus(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "check" -> Bukkit.getScheduler().runTaskAsynchronously(this, () -> checkAll(sender, false));
            case "update" -> Bukkit.getScheduler().runTaskAsynchronously(this, () -> checkAll(sender, true));
            case "reload" -> {
                reloadUpdater();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Configurazione ricaricata.");
            }
            default -> sender.sendMessage(PREFIX + ChatColor.YELLOW
                    + "Uso: /" + label + " <status|check|update|reload>");
        }
        return true;
    }

    private void showStatus(CommandSender sender) {
        sender.sendMessage(PREFIX + ChatColor.AQUA + "Plugin monitorati:");
        for (TrackedPlugin target : tracked) {
            Plugin installed = Bukkit.getPluginManager().getPlugin(target.name());
            String current = installed == null ? "non installato" : installed.getPluginMeta().getVersion();
            String latest;
            synchronized (latestVersions) {
                latest = latestVersions.getOrDefault(target.name(), "non ancora controllata");
            }
            String staged = stagedVersion(target.name());
            sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE + target.name()
                    + ChatColor.GRAY + " [" + target.channel().name().toLowerCase(Locale.ROOT)
                    + "]: installata " + current + ", ultima " + latest
                    + (staged == null ? "" : ", pronta " + staged));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("status", "check", "update", "reload").stream()
                .filter(value -> value.startsWith(prefix)).toList();
    }
}
