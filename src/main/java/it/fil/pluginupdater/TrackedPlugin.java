package it.fil.pluginupdater;

import java.util.regex.Pattern;

final class TrackedPlugin {
    private final String name;
    private final String repository;
    private final Pattern assetPattern;
    private final UpdateChannel channel;
    private final String devJobUrl;
    private final String devReleaseTag;
    private final String geyserPlatform;

    TrackedPlugin(String name, String repository, Pattern assetPattern,
                  UpdateChannel channel, String devJobUrl, String devReleaseTag) {
        this(name, repository, assetPattern, channel, devJobUrl, devReleaseTag, "");
    }

    TrackedPlugin(String name, String repository, Pattern assetPattern,
                  UpdateChannel channel, String devJobUrl, String devReleaseTag,
                  String geyserPlatform) {
        this.name = name;
        this.repository = repository;
        this.assetPattern = assetPattern;
        this.channel = channel;
        this.devJobUrl = devJobUrl;
        this.devReleaseTag = devReleaseTag;
        this.geyserPlatform = geyserPlatform;
    }

    String name() { return name; }
    String repository() { return repository; }
    Pattern assetPattern() { return assetPattern; }
    UpdateChannel channel() { return channel; }
    String devJobUrl() { return devJobUrl; }
    String devReleaseTag() { return devReleaseTag; }
    String geyserPlatform() { return geyserPlatform; }
}
