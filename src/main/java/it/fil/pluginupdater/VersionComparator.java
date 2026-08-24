package it.fil.pluginupdater;

import java.util.ArrayList;
import java.util.List;

final class VersionComparator {
    private VersionComparator() {
    }

    static int compare(String left, String right) {
        List<String> a = parts(normalize(left));
        List<String> b = parts(normalize(right));
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            String x = i < a.size() ? a.get(i) : "0";
            String y = i < b.size() ? b.get(i) : "0";
            int result = comparePart(x, y);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static String normalize(String version) {
        String value = version == null ? "" : version.trim();
        return value.startsWith("v") || value.startsWith("V") ? value.substring(1) : value;
    }

    private static List<String> parts(String version) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Boolean digit = null;
        for (char c : version.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) {
                flush(result, current);
                digit = null;
                continue;
            }
            boolean nowDigit = Character.isDigit(c);
            if (digit != null && digit != nowDigit) {
                flush(result, current);
            }
            current.append(Character.toLowerCase(c));
            digit = nowDigit;
        }
        flush(result, current);
        return result;
    }

    private static void flush(List<String> result, StringBuilder value) {
        if (!value.isEmpty()) {
            result.add(value.toString());
            value.setLength(0);
        }
    }

    private static int comparePart(String a, String b) {
        boolean aNumber = a.chars().allMatch(Character::isDigit);
        boolean bNumber = b.chars().allMatch(Character::isDigit);
        if (aNumber && bNumber) {
            String x = a.replaceFirst("^0+(?!$)", "");
            String y = b.replaceFirst("^0+(?!$)", "");
            int length = Integer.compare(x.length(), y.length());
            return length != 0 ? length : x.compareTo(y);
        }
        if (aNumber != bNumber) {
            return aNumber ? 1 : -1;
        }
        return a.compareTo(b);
    }
}
