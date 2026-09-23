package movies.core.conv;

import movies.core.FileNameParts;
import movies.core.NamingConvention;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Shared helpers for the built-in naming conventions: extension splitting,
 * quality/language token recognition and small parsing utilities.
 */
public abstract class AbstractConvention implements NamingConvention {

    /** Extensions recognised as video or subtitle files. */
    public static final String[] MEDIA_EXTENSIONS = {
            ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".mpg", ".mpeg", ".ts", ".wmv",
            ".srt", ".vtt", ".ass", ".ssa", ".sub"
    };

    /** Common language tokens found in release names. */
    protected static final String[] LANGUAGE_TOKENS = {
            "english", "eng", "en"
    };

    /** Source/codec tags that end an episode title fragment. */
    private static final java.util.Set<String> TECH_WORDS = new java.util.HashSet<String>(Arrays.asList(
            "hdtv", "hdtvrip", "webrip", "webdl", "web-dl", "web", "bluray", "bdrip", "brrip",
            "dvdrip", "hdrip", "hdr", "aac", "ac3", "eac3", "hevc", "avc", "xvid", "amzn",
            "nf", "hmax", "atvp", "proper", "repack", "remastered", "extended", "cam"));

    /** The tail of a title fragment: everything after the readable title. */
    protected static final class TechTail {
        public final String title;
        public final int quality;
        public final String language;
        public final List<String> tags = new ArrayList<String>();

        TechTail(String title, int quality, String language, List<String> tags) {
            this.title = title;
            this.quality = quality;
            this.language = language;
            this.tags.addAll(tags);
        }
    }

    /**
     * Splits trailing technical tokens ("HDTV.x264-FLEET.en", "720p.WEB.en")
     * off a title fragment. Natural words ("P.O.W") are never eaten: the walk
     * stops at the first token that is not a known quality/codec/source/language.
     */
    protected static TechTail splitTechnicalTail(String fragment) {
        List<String> tokens = new ArrayList<String>(Arrays.asList(fragment.split("\\.")));
        List<String> tags = new ArrayList<String>();
        int quality = 0;
        String language = "";
        while (tokens.size() > 1) {
            String token = tokens.get(tokens.size() - 1).trim();
            String t = token.toLowerCase(Locale.ROOT);
            if (quality == 0 && qualityTokenValue(token) > 0) {
                quality = qualityTokenValue(token);
            } else if (language.isEmpty() && isLanguageToken(t)) {
                language = token;
            } else if (t.matches("(x264|x265|h264|h265|hevc|avc|xvid)(-.+)?")) {
                tags.add(0, token);
            } else if (TECH_WORDS.contains(t)) {
                tags.add(0, token);
            } else {
                break;
            }
            tokens.remove(tokens.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (sb.length() > 0) sb.append('.');
            sb.append(token);
        }
        return new TechTail(sb.toString().trim(), quality, language, tags);
    }

    /** Parses "1080P", "720p" into 1080 / 720, or 0 when the token is not a quality. */
    protected static int qualityTokenValue(String token) {
        if (token == null) return 0;
        String t = token.toLowerCase(Locale.ROOT);
        if (t.length() < 4 || t.length() > 5) return 0;
        if (t.charAt(t.length() - 1) != 'p') return 0;
        String digits = t.substring(0, t.length() - 1);
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) return 0;
        }
        return Integer.parseInt(digits);
    }

    /** True when the token (case-insensitive) names a language we track. */
    protected static boolean isLanguageToken(String token) {
        if (token == null || token.isEmpty()) return false;
        String t = token.toLowerCase(Locale.ROOT);
        for (String lang : LANGUAGE_TOKENS) {
            if (t.equals(lang)) return true;
        }
        return false;
    }

    /**
     * Splits a file name into stem and extension. The extension is returned
     * (lowercase, with dot) when recognised as a media extension, otherwise the
     * whole name is the stem and the extension is empty. This lets the same
     * parser work on file names and on folder names.
     */
    protected static String[] splitStem(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : MEDIA_EXTENSIONS) {
            if (lower.endsWith(ext) && name.length() > ext.length()) {
                return new String[] { name.substring(0, name.length() - ext.length()), ext };
            }
        }
        return new String[] { name, "" };
    }

    /** Trims spaces and separator characters from both ends of a fragment. */
    protected static String trimSeparators(String s) {
        int start = 0;
        int end = s.length();
        while (start < end) {
            char c = s.charAt(start);
            if (c == ' ' || c == '_' || c == '.' || c == '-') start++; else break;
        }
        while (end > start) {
            char c = s.charAt(end - 1);
            if (c == ' ' || c == '_' || c == '.' || c == '-') end--; else break;
        }
        return s.substring(start, end);
    }

    /** Converts underscore tokens ("Can_t") into spaced words. */
    protected static String underscoresToSpaces(String s) {
        return s.replace('_', ' ');
    }

    protected static FileNameParts baseParts(String id, String originalName, String extension) {
        FileNameParts parts = new FileNameParts();
        parts.setConvention(id);
        parts.setOriginalName(originalName);
        parts.setExtension(extension);
        return parts;
    }

    /** Joins fragments with a separator, skipping empty fragments. */
    protected static String join(String separator, String... fragments) {
        StringBuilder sb = new StringBuilder();
        for (String fragment : fragments) {
            if (fragment == null || fragment.isEmpty()) continue;
            if (sb.length() > 0) sb.append(separator);
            sb.append(fragment);
        }
        return sb.toString();
    }

    /** Parses an integer or returns the fallback instead of throwing. */
    protected static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Collects every "(...)" group found in a fragment. */
    protected static List<String> extractParenthesised(String s) {
        List<String> found = new ArrayList<String>();
        int index = s.indexOf('(');
        while (index >= 0) {
            int close = s.indexOf(')', index + 1);
            if (close < 0) break;
            found.add(s.substring(index + 1, close));
            index = s.indexOf('(', close + 1);
        }
        return found;
    }

    /** Removes every "(...)" group (and surrounding spaces) from a fragment. */
    protected static String stripParenthesised(String s) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') { depth++; continue; }
            if (c == ')') { if (depth > 0) depth--; continue; }
            if (depth == 0) sb.append(c);
        }
        return sb.toString();
    }

    /** Standard four digit year check. */
    protected static boolean isYear(String token) {
        if (token == null || token.length() != 4) return false;
        for (int i = 0; i < 4; i++) {
            if (!Character.isDigit(token.charAt(i))) return false;
        }
        int value = Integer.parseInt(token);
        return value >= 1900 && value <= 2100;
    }

    /** True when the string is a lowercase hex hash such as fzmovies appends. */
    protected static boolean isHexHash(String token) {
        if (token == null || token.length() < 8 || token.length() > 64) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) return false;
        }
        return true;
    }

    /** Parses "S01" (season) or "E01" (episode) tokens; returns 0 when not one. */
    protected static int seasonTokenValue(String token) {
        if (token == null || token.length() < 2 || token.length() > 4) return 0;
        return letterTokenValue(token, 's');
    }

    protected static int episodeTokenValue(String token) {
        if (token == null || token.length() < 2 || token.length() > 4) return 0;
        return letterTokenValue(token, 'e');
    }

    private static int letterTokenValue(String token, char letter) {
        String t = token.toLowerCase(Locale.ROOT);
        if (t.charAt(0) != letter) return 0;
        String digits = t.substring(1);
        if (digits.isEmpty()) return 0;
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) return 0;
        }
        int value = Integer.parseInt(digits);
        return value > 0 ? value : 0;
    }

    /** Normalises "S01E03" style season/episode tokens; returns null when not one. */
    protected static int[] parseSeasonEpisode(String token) {
        if (token == null || token.length() < 4) return null;
        String t = token.toLowerCase(Locale.ROOT);
        int s = t.indexOf('s');
        int e = t.indexOf('e', s + 1);
        if (s != 0 || e < 2 || e == t.length() - 1) return null;
        String season = t.substring(1, e);
        String episode = t.substring(e + 1);
        if (season.isEmpty() || episode.length() > 3) return null;
        for (int i = 0; i < season.length(); i++) if (!Character.isDigit(season.charAt(i))) return null;
        for (int i = 0; i < episode.length(); i++) if (!Character.isDigit(episode.charAt(i))) return null;
        return new int[] { Integer.parseInt(season), Integer.parseInt(episode) };
    }

    /** Formats "S01E02" with two digit padding. */
    protected static String seasonEpisode(int season, int episode) {
        return pad(season) + "E" + pad(episode);
    }

    protected static String pad(int number) {
        return number < 10 ? "0" + number : Integer.toString(number);
    }

    protected static boolean in(String[] haystack, String needle) {
        return Arrays.asList(haystack).contains(needle);
    }
}
