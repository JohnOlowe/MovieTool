package movies.subs;

import movies.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SubRip (.srt) model, parser and writer plus the VTT to SRT conversion.
 *
 * <p>Times are kept as milliseconds since the start of the track. The parser
 * is deliberately forgiving: block numbers are optional, timestamps may use
 * '.' or ',' for milliseconds and CRLF/CR/LF are all accepted.</p>
 */
public final class SubRip {

    /** One subtitle cue. */
    public static final class Cue {
        public long start;
        public long end;
        public String text;

        public Cue(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }

        public Cue copy() {
            return new Cue(start, end, text);
        }
    }

    private final List<Cue> cues = new ArrayList<Cue>();

    public List<Cue> cues() {
        return cues;
    }

    public void add(long start, long end, String text) {
        cues.add(new Cue(start, end, text));
    }

    public boolean isEmpty() {
        return cues.isEmpty();
    }

    // ---------------------------------------------------------------- parse

    /** Parses SRT content; unparsable blocks are skipped rather than fatal. */
    public static SubRip parseSrt(String content) {
        SubRip track = new SubRip();
        String normalised = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalised.split("\n\n+");
        for (String block : blocks) {
            parseSrtBlock(track, block);
        }
        return track;
    }

    private static void parseSrtBlock(SubRip track, String block) {
        String[] lines = block.split("\n");
        int timeLine = -1;
        for (int i = 0; i < Math.min(lines.length, 3); i++) {
            if (lines[i].contains("-->")) {
                timeLine = i;
                break;
            }
        }
        if (timeLine < 0 || timeLine + 1 >= lines.length) return;

        long[] times = parseTimes(lines[timeLine]);
        if (times == null) return;

        StringBuilder text = new StringBuilder();
        for (int i = timeLine + 1; i < lines.length; i++) {
            if (text.length() > 0) text.append('\n');
            text.append(lines[i]);
        }
        String body = text.toString().trim();
        if (body.isEmpty()) return;
        track.add(times[0], times[1], body);
    }

    /** Parses "HH:MM:SS,mmm --> HH:MM:SS,mmm" (also tolerates '.' and one missing hour). */
    public static long[] parseTimes(String line) {
        int arrow = line.indexOf("-->");
        if (arrow < 0) return null;
        String start = line.substring(0, arrow).trim();
        String end = line.substring(arrow + 3).trim();
        // Drop trailing cue settings ("align:start position:0%").
        int spaceInEnd = end.indexOf(' ');
        if (spaceInEnd >= 0) end = end.substring(0, spaceInEnd);
        long from = parseTimestamp(start);
        long to = parseTimestamp(end);
        if (from < 0 || to < 0) return null;
        return new long[] { from, to };
    }

    /** Parses one timestamp; returns -1 when malformed. */
    public static long parseTimestamp(String timestamp) {
        String t = timestamp.trim().replace(',', '.');
        // Strip a leading negative sign for parsing; callers decide on validity.
        boolean negative = t.startsWith("-");
        if (negative) t = t.substring(1);
        String[] parts = t.split(":");
        if (parts.length < 2 || parts.length > 3) return -1;
        long hours = 0;
        String minutesPart;
        String secondsPart;
        if (parts.length == 3) {
            if (parts[0].isEmpty()) return -1;
            hours = parseDigits(parts[0]);
            if (hours < 0) return -1;
            minutesPart = parts[1];
            secondsPart = parts[2];
        } else {
            minutesPart = parts[0];
            secondsPart = parts[1];
        }
        long minutes = parseDigits(minutesPart);
        if (minutes < 0) return -1;
        int dot = secondsPart.indexOf('.');
        long seconds;
        long millis;
        if (dot >= 0) {
            seconds = parseDigits(secondsPart.substring(0, dot));
            String fraction = secondsPart.substring(dot + 1);
            while (fraction.length() < 3) fraction = fraction + "0";
            if (fraction.length() > 3) fraction = fraction.substring(0, 3);
            millis = parseDigits(fraction);
        } else {
            seconds = parseDigits(secondsPart);
            millis = 0;
        }
        if (seconds < 0 || millis < 0) return -1;
        long total = ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis;
        return negative ? -total : total;
    }

    private static long parseDigits(String digits) {
        if (digits.isEmpty()) return -1;
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) return -1;
        }
        return Long.parseLong(digits);
    }

    // ---------------------------------------------------------------- write

    /** Formats the track back to standard SRT text. */
    public String toSrt() {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Cue cue : cues) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(index++).append('\n');
            sb.append(formatTimestamp(cue.start)).append(" --> ").append(formatTimestamp(cue.end)).append('\n');
            sb.append(cue.text);
        }
        sb.append('\n');
        return sb.toString();
    }

    /** "HH:MM:SS,mmm" */
    public static String formatTimestamp(long millis) {
        if (millis < 0) millis = 0;
        long h = millis / 3600000;
        long m = (millis % 3600000) / 60000;
        long s = (millis % 60000) / 1000;
        long ms = millis % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, ms);
    }

    // ---------------------------------------------------------------- files

    public static SubRip loadSrt(File file) throws IOException {
        return parseSrt(IoUtil.readText(file));
    }

    public void saveSrt(File file) throws IOException {
        IoUtil.writeText(file, toSrt());
    }

    // ------------------------------------------------------------ vtt input

    /**
     * Parses WebVTT content. Headers, NOTE/STYLE/REGION blocks, cue numbers
     * and cue settings are skipped; simple &lt;c&gt; styling tags are dropped
     * while &lt;i&gt;/&lt;b&gt;/&lt;u&gt; are kept.
     */
    public static SubRip parseVtt(String content) {
        SubRip track = new SubRip();
        String normalised = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalised.split("\n");
        int i = 0;
        // Header line: "WEBVTT" possibly followed by text.
        if (lines.length > 0 && lines[0].startsWith("WEBVTT")) i = 1;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.isEmpty()) { i++; continue; }
            if (line.startsWith("NOTE") || line.startsWith("STYLE") || line.startsWith("REGION")) {
                i = skipVttBlock(lines, i);
                continue;
            }
            // Optional cue identifier line: a timestamp must follow within two lines.
            int timestampIndex = i;
            if (!line.contains("-->")) {
                if (i + 1 < lines.length && lines[i + 1].contains("-->")) {
                    timestampIndex = i + 1;
                } else {
                    i++;
                    continue;
                }
            }
            long[] times = parseTimes(lines[timestampIndex]);
            if (times == null) { i = timestampIndex + 1; continue; }
            StringBuilder text = new StringBuilder();
            int j = timestampIndex + 1;
            for (; j < lines.length && !lines[j].trim().isEmpty(); j++) {
                String textLine = cleanVttText(lines[j]);
                if (textLine.isEmpty()) continue;
                if (text.length() > 0) text.append('\n');
                text.append(textLine);
            }
            if (text.length() > 0) {
                if (times[1] > times[0]) track.add(times[0], times[1], text.toString());
            }
            i = j;
        }
        return track;
    }

    private static int skipVttBlock(String[] lines, int start) {
        int i = start + 1;
        while (i < lines.length && !lines[i].trim().isEmpty()) i++;
        return i;
    }

    /** Removes <c> tags and <v Name>/&lt;audio&gt; wrappers, keeps i/b/u. */
    static String cleanVttText(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        int depth = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '<') {
                int close = line.indexOf('>', i + 1);
                if (close < 0) break;
                String tag = line.substring(i + 1, close).toLowerCase(Locale.ROOT);
                if (tag.startsWith("c") || tag.startsWith("/c") || tag.startsWith("v ")
                        || tag.startsWith("/v") || tag.startsWith("audio") || tag.startsWith("/audio")) {
                    i = close;
                    continue;
                }
                sb.append(line.substring(i, close + 1));
                i = close;
                continue;
            }
            if (c == '>') { depth++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    // ----------------------------------------------------------- operations

    /**
     * Sweeping two-track merge: every interval between boundaries carries the
     * text of whichever cues are active, stacked. Identical consecutive text
     * is coalesced. {@code secondOnTop} puts track B's lines above A's.
     */
    public static SubRip merge(SubRip first, SubRip second, boolean secondOnTop) {
        java.util.TreeSet<Long> boundaries = new java.util.TreeSet<Long>();
        for (Cue cue : first.cues) { boundaries.add(cue.start); boundaries.add(cue.end); }
        for (Cue cue : second.cues) { boundaries.add(cue.start); boundaries.add(cue.end); }

        SubRip merged = new SubRip();
        Long previous = null;
        for (long boundary : boundaries) {
            if (previous == null) { previous = boundary; continue; }
            long start = previous;
            long end = boundary;
            previous = boundary;
            if (end <= start) continue;
            long mid = start + (end - start) / 2;
            String a = activeText(first, mid);
            String b = activeText(second, mid);
            if (a.isEmpty() && b.isEmpty()) continue;
            String text = b.isEmpty() ? a : a.isEmpty() ? b
                    : secondOnTop ? b + "\n" + a : a + "\n" + b;
            merged.add(start, end, text);
        }
        // Coalesce identical neighbours that touch.
        List<Cue> compact = new ArrayList<Cue>();
        for (Cue cue : merged.cues) {
            if (!compact.isEmpty()) {
                Cue last = compact.get(compact.size() - 1);
                if (last.text.equals(cue.text) && last.end == cue.start) {
                    last.end = cue.end;
                    continue;
                }
            }
            compact.add(cue);
        }
        merged.cues.clear();
        merged.cues.addAll(compact);
        return merged;
    }

    private static String activeText(SubRip track, long time) {
        List<String> active = new ArrayList<String>();
        for (Cue cue : track.cues) {
            if (time >= cue.start && time < cue.end) active.add(cue.text);
        }
        StringBuilder sb = new StringBuilder();
        for (String text : active) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(text);
        }
        return sb.toString();
    }

    /** Shifts every cue by the given number of milliseconds (may be negative). */
    public void shift(long millis) {
        for (Cue cue : cues) {
            cue.start += millis;
            cue.end += millis;
            if (cue.start < 0) cue.start = 0;
            if (cue.end < cue.start) cue.end = cue.start;
        }
    }
}
