package it.fil.pluginupdater;

enum UpdateChannel {
    RELEASE,
    DEV;

    static UpdateChannel parse(String value) {
        return "dev".equalsIgnoreCase(value) ? DEV : RELEASE;
    }
}
