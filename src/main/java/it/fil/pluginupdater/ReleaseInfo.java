package it.fil.pluginupdater;

import java.net.URI;

record ReleaseInfo(String version, String assetName, URI downloadUri, long size,
                   String sha256, int buildNumber) {
    String displayVersion() {
        return buildNumber > 0 ? version + " build " + buildNumber : version;
    }
}
