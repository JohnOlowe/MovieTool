package movies;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;

public class VttToSrtConverter {

    public static final String SRT_FILE = "/storage/67FE-19FE/Movies/supergirl2026.vtt";
    
    public static String convertVttToSrt(File vttContent) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(vttContent));
        StringBuilder srtOutput = new StringBuilder();
        String line;
        int counter = 1;
        boolean readingSub = false;

        while ((line = reader.readLine()) != null) {
            line = line.trim();

            // Skip WEBVTT header and standard VTT notes
            if (line.startsWith("WEBVTT") || line.startsWith("NOTE")) {
                continue;
            }

            // Identify timestamp lines
            if (line.contains("-->")) {
                String[] times = line.split("-->");
                if (times.length >= 2) {
                    // Add a blank line between subtitle blocks (skip for the very first one)
                    if (counter > 1) {
                        srtOutput.append("\n");
                    }

                    // SRT requires a sequential index number
                    srtOutput.append(counter++).append("\n");

                    // Format and append the timestamps
                    srtOutput.append(formatTimestamp(times[0].trim()))
                        .append(" --> ")
                        .append(formatTimestamp(times[1].trim()))
                        .append("\n");

                    readingSub = true;
                }
            } else if (!line.isEmpty() && readingSub) {
                // This is the actual subtitle text
                srtOutput.append(line).append("\n");
            } else if (line.isEmpty()) {
                // Reset flag when we hit an empty line between blocks
                readingSub = false;
            }
        }

        return srtOutput.toString().trim();
    }

    private static String formatTimestamp(String time) {
        // 1. Strip out VTT positioning metadata (e.g., "align:start") if present
        time = time.split(" ")[0];

        // 2. Convert millisecond separator from period to comma
        time = time.replace('.', ',');

        // 3. Prepend hours if missing (VTT: MM:SS,mmm -> SRT: HH:MM:SS,mmm)
        if (time.length() == 9) { 
            time = "00:" + time;
        }

        return time;
    }

    public static void main(String[] args) {
        try {
            String srtResult = convertVttToSrt(new File(SRT_FILE));
            FileWriter fw = new FileWriter(new File(SRT_FILE.substring(0, SRT_FILE.length() - 3) + "srt"));
            fw.write(srtResult);
            fw.close();
            System.out.println("Successful");
        } catch (IOException e) {
            System.err.println("Error parsing VTT content: " + e.getMessage());
        }
    }
}

