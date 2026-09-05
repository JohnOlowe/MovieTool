package movies.core.conv;

import movies.core.FileNameParts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * NKIRI style names: dot separated with a bracketed site token somewhere.
 *
 * <pre>
 *   The.Hill.(NKIRI.COM).2023.AMZN.WEBRip.DOWNLOADED.FROM.NKIRI.COM.mkv
 * </pre>
 */
public class NkiriConvention extends AbstractConvention {

    private static final String[] NOISE_WORDS = {
            "downloaded", "from", "webrip", "web-dl", "amzn", "nf", "web", "dl", "1080p",
            "720p", "480p", "2160p", "x264", "x265", "hevc", "aac", "mp4", "mkv", "hd", "bluray"
    };

    @Override
    public String id() { return "nkiri"; }

    @Override
    public String displayName() { return "NKIRI"; }

    @Override
    public String example() { return "Title.(NKIRI.COM).2023.WEBRip.mkv"; }

    @Override
    public FileNameParts parse(String fileName) {
        String[] stemExt = splitStem(fileName);
        String stem = stemExt[0];
        String ext = stemExt[1];
        if (ext.isEmpty() || stem.isEmpty()) return null;

        // The site sits in a parenthesised group that may contain dots, so it
        // is extracted from the raw stem before tokenising on dots.
        java.util.regex.Matcher groupMatcher = java.util.regex.Pattern.compile("\\([^)]*\\)").matcher(stem);
        List<String> groups = new ArrayList<String>();
        boolean hasSite = false;
        while (groupMatcher.find()) {
            String group = groupMatcher.group();
            groups.add(group.substring(1, group.length() - 1));
            if (group.toLowerCase(Locale.ROOT).contains("nkiri")) hasSite = true;
        }
        String cleaned = stem.replaceAll("\\([^)]*\\)", "");
        if (!hasSite) return null;

        List<String> tokens = new ArrayList<String>();
        for (String token : cleaned.split("\\.")) {
            if (!token.isEmpty()) tokens.add(token);
        }
        if (tokens.size() < 2) return null;

        int yearIndex = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (isYear(tokens.get(i))) { yearIndex = i; break; }
        }
        if (yearIndex < 0) return null;

        FileNameParts parts = baseParts(id(), fileName, ext);
        parts.setYear(Integer.parseInt(tokens.get(yearIndex)));

        StringBuilder title = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (i == yearIndex) continue;
            String lower = token.toLowerCase(Locale.ROOT);
            int quality = qualityTokenValue(token);
            if (quality > 0 && parts.getQuality() == 0) { parts.setQuality(quality); continue; }
            if (inNoise(lower)) continue;
            if (i < yearIndex) {
                if (title.length() > 0) title.append(' ');
                title.append(token);
            } else if (token.length() > 1 && !parts.getTags().contains(token)) {
                parts.getTags().add(token);
            }
        }
        parts.setTitle(title.toString().trim());
        if (parts.getTitle().isEmpty()) return null;
        // Site group first so the rebuild order matches the original style.
        for (int i = groups.size() - 1; i >= 0; i--) {
            if (!parts.getTags().contains(groups.get(i))) parts.getTags().add(0, groups.get(i));
        }
        return parts;
    }

    @Override
    public String build(FileNameParts p) {
        StringBuilder sb = new StringBuilder(p.getTitle().replace(' ', '.'));
        if (p.getYear() > 0) sb.append('.').append(p.getYear());
        for (String tag : p.getTags()) sb.append('.').append(tag.replace(' ', '.'));
        sb.append(p.getExtension());
        return sb.toString();
    }

    private static boolean inNoise(String lower) {
        for (String word : NOISE_WORDS) {
            if (lower.equals(word)) return true;
        }
        return false;
    }
}
