package movies;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class MergeSubtitles {
    public static final String SUB_1 = "/storage/67FE-19FE/Movies/Movies/The_Covenant_1080P_1.srt";
    public static final String SUB_2 = "/storage/67FE-19FE/Movies/Movies/The_Covenant_English.srt";
    public static final String OUTPUT = "/storage/67FE-19FE/Movies/Movies/The_Covenant_1080P.srt";

    static class Sub {
        long start, end;
        String text;

        public Sub(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    public static void main(String[] args) {
        // Replace these with your actual file paths
        String file1 = SUB_1;
        String file2 = SUB_2;
        String outputFile = OUTPUT;

        try {
            List<Sub> subs1 = parseSRT(file1);
            List<Sub> subs2 = parseSRT(file2);

            // 1. Gather all unique time boundaries (start and end times)
            TreeSet<Long> boundaries = new TreeSet<>();
            for (Sub s : subs1) { boundaries.add(s.start); boundaries.add(s.end); }
            for (Sub s : subs2) { boundaries.add(s.start); boundaries.add(s.end); }

            List<Long> timeList = new ArrayList<>(boundaries);
            List<Sub> mergedSubs = new ArrayList<>();

            // 2. Sweep through the intervals and combine active text
            for (int i = 0; i < timeList.size() - 1; i++) {
                long start = timeList.get(i);
                long end = timeList.get(i + 1);

                // Sample the midpoint of the interval to see what text is active
                long mid = start + (end - start) / 2; 

                String t1 = getActiveText(subs1, mid);
                String t2 = getActiveText(subs2, mid);

                StringBuilder combined = new StringBuilder();
                if (!t1.isEmpty() && !t2.isEmpty()) {
                    combined.append(t1).append("\n").append(t2); // Places them on top of each other
                } else if (!t1.isEmpty()) {
                    combined.append(t1);
                } else if (!t2.isEmpty()) {
                    combined.append(t2);
                }

                // 3. Add to our merged list, avoiding empty gaps and combining identical consecutive blocks
                if (combined.length() > 0) {
                    if (!mergedSubs.isEmpty() && 
                        mergedSubs.get(mergedSubs.size() - 1).text.equals(combined.toString()) && 
                        mergedSubs.get(mergedSubs.size() - 1).end == start) {
                        // Extend the previous block if the text hasn't changed
                        mergedSubs.get(mergedSubs.size() - 1).end = end;
                    } else {
                        mergedSubs.add(new Sub(start, end, combined.toString()));
                    }
                }
            }

            writeSRT(mergedSubs, outputFile);
            System.out.println("Subtitles successfully merged into " + outputFile);

        } catch (IOException e) {
            System.err.println("Error processing files: " + e.getMessage());
        }
    }

    private static String getActiveText(List<Sub> subs, long time) {
        List<String> active = new ArrayList<>();
        for (Sub s : subs) {
            if (time >= s.start && time < s.end) {
                active.add(s.text);
            }
        }
        return String.join("\n", active);
    }

    private static List<Sub> parseSRT(String filename) throws IOException {
        List<Sub> subs = new ArrayList<>();
        String content = new String(Files.readAllBytes(Paths.get(filename)));

        // Split by double newline to get individual blocks
        String[] blocks = content.split("\\r?\\n\\r?\\n");
        Pattern timePattern = Pattern.compile("(\\d{2}:\\d{2}:\\d{2},\\d{3}) --> (\\d{2}:\\d{2}:\\d{2},\\d{3})");

        for (String block : blocks) {
            String[] lines = block.split("\\r?\\n");
            if (lines.length >= 3) {
                Matcher m = timePattern.matcher(lines[1]);
                if (m.matches()) {
                    long start = parseTime(m.group(1));
                    long end = parseTime(m.group(2));

                    StringBuilder sb = new StringBuilder();
                    for (int i = 2; i < lines.length; i++) {
                        sb.append(lines[i]).append("\n");
                    }
                    subs.add(new Sub(start, end, sb.toString().trim()));
                }
            }
        }
        return subs;
    }

    private static void writeSRT(List<Sub> subs, String filename) throws IOException {
        try (PrintWriter out = new PrintWriter(new FileWriter(filename))) {
            int index = 1;
            for (Sub s : subs) {
                out.println(index++);
                out.println(formatTime(s.start) + " --> " + formatTime(s.end));
                out.println(s.text);
                out.println();
            }
        }
    }

    private static long parseTime(String time) {
        String[] parts = time.split("[:,]");
        return Long.parseLong(parts[0]) * 3600000 +
            Long.parseLong(parts[1]) * 60000 +
            Long.parseLong(parts[2]) * 1000 +
            Long.parseLong(parts[3]);
    }

    private static String formatTime(long millis) {
        long h = millis / 3600000;
        long m = (millis % 3600000) / 60000;
        long s = (millis % 60000) / 1000;
        long ms = millis % 1000;
        return String.format("%02d:%02d:%02d,%03d", h, m, s, ms);
    }
}

