package it.fil.pluginupdater;

import java.util.regex.Pattern;

final class TrackedPlugin {
    private final String name;
    private final String repository;
    private final Pattern assetPattern;
    private final UpdateChannel channel;
    private final String devJobUrl;

    TrackedPlugin(String name, String repository, Pattern assetPattern,
                  UpdateChannel channel, String devJobUrl) {
        this.name = name;
        this.repository = repository;
        this.assetPattern = assetPattern;
        this.channel = channel;
        this.devJobUrl = devJobUrl;
    }

    String name() { return name; }
    String repository() { return repository; }
    Pattern assetPattern() { return assetPattern; }
    UpdateChannel channel() { return channel; }
    String devJobUrl() { return devJobUrl; }
}
