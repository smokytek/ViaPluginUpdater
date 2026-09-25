package it.fil.pluginupdater;

import java.util.regex.Pattern;

public final class UpdaterSelfTest {
    public static void main(String[] args) throws Exception {
        require(VersionComparator.compare("5.11.0", "5.9.1") > 0, "confronto versione crescente");
        require(VersionComparator.compare("v5.9.1", "5.9.1") == 0, "prefisso v");
        require(VersionComparator.compare("5.9.1", "5.11.0") < 0, "confronto versione decrescente");

        GitHubReleaseClient client = new GitHubReleaseClient("");
        testRelease(client, "ViaVersion", "ViaVersion/ViaVersion");
        testRelease(client, "ViaBackwards", "ViaVersion/ViaBackwards");
        testRelease(client, "ViaRewind", "ViaVersion/ViaRewind");
        testDev(client, "ViaVersion", "https://ci.viaversion.com/job/ViaVersion");
        testDev(client, "ViaBackwards", "https://ci.viaversion.com/view/ViaBackwards/job/ViaBackwards");
        testDev(client, "ViaRewind", "https://ci.viaversion.com/view/ViaRewind/job/ViaRewind");
        testRelease(client, "ProtocolLib", "dmulloy2/ProtocolLib", "^ProtocolLib\\.jar$");
        testTaggedDev(client, "ProtocolLib", "dmulloy2/ProtocolLib", "dev-build",
                "^ProtocolLib-Spigot\\.jar$");
        testRelease(client, "packetevents", "retrooper/packetevents",
                "^packetevents-spigot-(?!.*-(?:javadoc|sources)\\.jar$)[0-9].*\\.jar$");
        testDev(client, "packetevents", "https://ci.codemc.io/job/retrooper/job/packetevents",
                "^packetevents-spigot-(?!.*-(?:javadoc|sources)\\.jar$)[0-9].*\\.jar$");
        testRelease(client, "SkinsRestorer", "SkinsRestorer/SkinsRestorer",
                "^SkinsRestorer\\.jar$");
        testDev(client, "SkinsRestorer",
                "https://ci.codemc.io/job/SkinsRestorer/job/SkinsRestorer",
                "^SkinsRestorer\\.jar$", false);
        testGeyserFloodgate(client, "spigot");
        testGeyserFloodgate(client, "velocity");
        testVelocityProxy();
    }

    private static void testDev(GitHubReleaseClient client, String name, String jobUrl) throws Exception {
        testDev(client, name, jobUrl, "^" + name + "-[0-9].*\\.jar$");
    }

    private static void testDev(GitHubReleaseClient client, String name, String jobUrl,
                                String pattern) throws Exception {
        testDev(client, name, jobUrl, pattern, true);
    }

    private static void testDev(GitHubReleaseClient client, String name, String jobUrl,
                                String pattern, boolean versionInFileName) throws Exception {
        TrackedPlugin plugin = new TrackedPlugin(name, "ViaVersion/" + name,
                Pattern.compile(pattern), UpdateChannel.DEV, jobUrl, "");
        ReleaseInfo build = client.latest(plugin).get();
        require(build.buildNumber() > 0, "numero build dev " + name);
        require(versionInFileName ? !build.version().isEmpty()
                : "dev".equals(build.version()), "versione dev " + name);
        require(client.download(build).length > 0, "download dev " + name);
        System.out.println("OK DEV: " + name + " " + build.displayVersion());
    }

    private static void testRelease(GitHubReleaseClient client, String name, String repository) throws Exception {
        testRelease(client, name, repository, "^" + name + "-[0-9].*\\.jar$");
    }

    private static void testRelease(GitHubReleaseClient client, String name, String repository,
                                    String pattern) throws Exception {
        TrackedPlugin plugin = new TrackedPlugin(name, repository,
                Pattern.compile(pattern), UpdateChannel.RELEASE, "", "dev-build");
        ReleaseInfo release = client.latest(plugin).get();
        require(plugin.assetPattern().matcher(release.assetName()).matches(), "asset " + name);
        require(release.size() > 0, "dimensione asset " + name);
        require(client.download(release).length == release.size(), "download e checksum " + name);
        System.out.println("OK: " + name + " " + release.version() + ", " + release.size() + " byte");
    }

    private static void testTaggedDev(GitHubReleaseClient client, String name, String repository,
                                      String tag, String pattern) throws Exception {
        TrackedPlugin plugin = new TrackedPlugin(name, repository, Pattern.compile(pattern),
                UpdateChannel.DEV, "", tag);
        ReleaseInfo release = client.latest(plugin).get();
        require(release.buildNumber() > 0, "id asset dev " + name);
        require(client.download(release).length == release.size(), "download dev GitHub " + name);
        System.out.println("OK DEV: " + name + " " + release.displayVersion());
    }

    private static void testGeyserFloodgate(GitHubReleaseClient client, String platform) throws Exception {
        TrackedPlugin plugin = new TrackedPlugin("floodgate", "GeyserMC/Floodgate",
                Pattern.compile("^floodgate-" + platform + "\\.jar$"),
                UpdateChannel.DEV, "", "", platform);
        ReleaseInfo release = client.latest(plugin).get();
        require(release.buildNumber() > 0, "build Floodgate " + platform);
        require(client.download(release).length > 0, "download Floodgate " + platform);
        System.out.println("OK DEV: Floodgate " + platform + " " + release.displayVersion());
    }

    private static void testVelocityProxy() throws Exception {
        PaperVelocityClient client = new PaperVelocityClient();
        VelocityBuild build = client.latestStable();
        require(build.build() > 0, "build Velocity");
        require(build.fileName().endsWith(".jar"), "asset Velocity");
        require(client.download(build).length == build.size(), "download e checksum Velocity");
        System.out.println("OK: Velocity " + build.version() + " build " + build.build());
    }

    private static void require(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError("Test fallito: " + name);
        }
    }
}
