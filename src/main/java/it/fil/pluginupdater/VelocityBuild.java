package it.fil.pluginupdater;

import java.net.URI;

final class VelocityBuild {
    private final String version;
    private final int build;
    private final String fileName;
    private final URI downloadUri;
    private final long size;
    private final String sha256;

    VelocityBuild(String version, int build, String fileName, URI downloadUri,
                  long size, String sha256) {
        this.version = version;
        this.build = build;
        this.fileName = fileName;
        this.downloadUri = downloadUri;
        this.size = size;
        this.sha256 = sha256;
    }

    String version() { return version; }
    int build() { return build; }
    String fileName() { return fileName; }
    URI downloadUri() { return downloadUri; }
    long size() { return size; }
    String sha256() { return sha256; }
}
