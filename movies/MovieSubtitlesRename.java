package movies;

import java.io.File;
import java.io.FilenameFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Scanner;
import java.io.PrintStream;

public class MovieSubtitlesRename {
    public static String INPUT_FOLDER = "/storage/EBC3-7839/Movies/Series Movies/Outer Banks/";
    public static String MOVIE_NAME = "Outer_Banks";

    // public static String INPUT_FOLDER = "/storage/emulated/0/Movies/Black Lightning/Black_Lightning";
    // public static String MOVIE_NAME = "Black_Lightning";

    public static void main(String[] args) {
        if (true) {
            //stripCopiedNetflix(INPUT_FOLDER + "/titles.list", INPUT_FOLDER + "/episodes-name.list");
            //return;
        }
        if (INPUT_FOLDER.endsWith("/")) INPUT_FOLDER = INPUT_FOLDER.substring(0, INPUT_FOLDER.length() - 1);
        
        File[] contentFiles = new File(INPUT_FOLDER).listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String fileName) {
                    return fileName.startsWith(MOVIE_NAME);
                }
            });
        System.out.println(contentFiles.length);
        eachFile: for (File file : contentFiles) {
            int season = 0;
            int episode = 0;
            if (file.isDirectory()) {
                if (file.getName().equals(MOVIE_NAME)) {

                } else {
                    // Since we're parsing a folder, there is no extension after the episode detail
                    String details = file.getName().substring(MOVIE_NAME.length() + 1);
                    String[] detailsArr = details.split("_");
                    if (detailsArr.length != 2) continue eachFile;
                    try {
                        season = Integer.parseInt(detailsArr[0].substring(1));
                        episode = Integer.parseInt(detailsArr[1].substring(1));
                        // System.out.println("S" + season + "_E" + episode);
                    } catch (Exception e) {
                        System.out.println("Exception 1");
                        continue eachFile;
                    }
                }
                for (File dirFile : contentFiles) {
                    if (dirFile.isDirectory()) continue;
                    if (season == 0 || episode == 0) {
                        // This is not dealing with the fact that a series movie and an ordinary movie
                        // might start with the same name
                        if (dirFile.getName().startsWith(MOVIE_NAME + "_") && dirFile.getName().endsWith(".mp4")) {
                            if (file.list().length == 1 && file.list()[0].endsWith(".srt")) {
                                copy(new File(file, file.list()[0]), new File(INPUT_FOLDER), dirFile.getName().substring(0, dirFile.getName().length() - ".mp4".length()) + ".srt");
                            } else if (new File(file, MOVIE_NAME + "_English.srt").exists()) {
                                copy(new File(file, MOVIE_NAME + "_English.srt"), new File(INPUT_FOLDER), dirFile.getName().substring(0, dirFile.getName().length() - ".mp4".length()) + ".srt");
                            }
                        }
                        continue eachFile;
                    } else {
                        if (!dirFile.getName().endsWith(".mp4")) continue;
                        String movieDetails = dirFile.getName().substring(MOVIE_NAME.length() + 1, dirFile.getName().length() - ".mp4".length());
                        String[] movieDetailsArray = movieDetails.split("_");
                        int movieSeason, movieEpisode;
                        try {
                            if (movieDetailsArray.length != 3) continue;
                            movieSeason = Integer.parseInt(movieDetailsArray[1].substring(1));
                            movieEpisode = Integer.parseInt(movieDetailsArray[2].substring(1));
                        } catch (Exception e) {
                            System.out.println("Exception 2");
                            continue;
                        }
                        if (season == movieSeason && episode == movieEpisode) {
                            if (file.list().length == 1 && file.list()[0].endsWith(".srt")) {
                                copy(new File(file, file.list()[0]), new File(INPUT_FOLDER), dirFile.getName().substring(0, dirFile.getName().length() - ".mp4".length()) + ".srt");
                            } else if (new File(file, MOVIE_NAME + "_S" + movieSeason + "_E" + movieEpisode + "_English.srt").exists()) {
                                copy(new File(file, MOVIE_NAME + "_S" + movieSeason + "_E" + movieEpisode + "_English.srt"), new File(INPUT_FOLDER), dirFile.getName().substring(0, dirFile.getName().length() - ".mp4".length()) + ".srt");
                            }
                        }  
                    }
                }
            }
        }
        System.out.println("Done");
    }

    public static void copy(File file, File destFolder, String fileName) {
        // System.out.println("Replacing " + file.getName() + " with " + fileName);
        try {
            try (FileInputStream inputStream = new FileInputStream(file); FileOutputStream outputStream = new FileOutputStream(new File(destFolder, fileName))) {
                int read = 0;
                byte[] buffer = new byte[1024];
                while ((read = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, read);
                }
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    private static void stripCopiedNetflix(String inputPath, String outputPath) {
        try {
            Scanner input = new Scanner(new FileInputStream(inputPath));
            PrintStream out = new PrintStream(new FileOutputStream(outputPath));
            
            while (input.hasNextLine()) {
                String line = input.nextLine();
                if (line.contains(" ∙ ")) {
                    out.println(line);
                }
            }
            
            input.close();
            out.close();
            
            System.out.println("Successful");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

}
