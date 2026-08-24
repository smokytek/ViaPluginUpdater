package it.fil.pluginupdater;

import java.net.URI;

final class ReleaseInfo {
    private final String version;
    private final String assetName;
    private final URI downloadUri;
    private final long size;
    private final String sha256;
    private final int buildNumber;

    ReleaseInfo(String version, String assetName, URI downloadUri, long size,
                String sha256, int buildNumber) {
        this.version = version;
        this.assetName = assetName;
        this.downloadUri = downloadUri;
        this.size = size;
        this.sha256 = sha256;
        this.buildNumber = buildNumber;
    }

    String version() { return version; }
    String assetName() { return assetName; }
    URI downloadUri() { return downloadUri; }
    long size() { return size; }
    String sha256() { return sha256; }
    int buildNumber() { return buildNumber; }

    String displayVersion() {
        return buildNumber > 0 ? version + " build " + buildNumber : version;
    }
}
