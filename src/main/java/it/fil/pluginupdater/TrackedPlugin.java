package it.fil.pluginupdater;

import java.util.regex.Pattern;

record TrackedPlugin(String name, String repository, Pattern assetPattern,
                     UpdateChannel channel, String devJobUrl) {
}
