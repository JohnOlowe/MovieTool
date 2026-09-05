package movies;

import java.io.File;
import java.io.FilenameFilter;
import java.util.HashMap;

public class CopySubs {
    
    public static String INPUT_FOLDER = MovieSubtitlesRename.INPUT_FOLDER;
    public static String MOVIE_NAME = MovieSubtitlesRename.MOVIE_NAME;
    
    private static HashMap<String, SubtitleFile> movieboxSubs = new HashMap<>();
    private static HashMap<String, SubtitleFile> tvSubtitleSubs = new HashMap<>();
    private static HashMap<String, MovieFile> movieboxVids = new HashMap<>();
    
    public static void main(String[] args) {
        if (true) {
            //stripCopiedNetflix("/storage/emulated/0/Movies/Walter Boys Netflix.txt", "/storage/emulated/0/Movies/My Life With The Walter Boys Netflix.txt");
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
                                MovieSubtitlesRename.copy(new File(file, file.list()[0]), new File(INPUT_FOLDER), dirFile.getName().substring(0, dirFile.getName().length() - ".mp4".length()) + ".srt");
                            } else if (new File(file, MOVIE_NAME + "_English.srt").exists()) {
                                MovieSubtitlesRename.copy(new File(file, MOVIE_NAME + "_English.srt"), new File(INPUT_FOLDER), dirFile.getName().substring(0, dirFile.getName().length() - ".mp4".length()) + ".srt");
                            }
                        }
                        continue eachFile;
                    } else {
                        if (!dirFile.getName().endsWith(".mp4")) continue;
                        // This fails if any of the movie files are not in the TV format
                        String movieDetails;
                        try {
                            // It failed, and I had to do something quickly
                            movieDetails = dirFile.getName().substring(MOVIE_NAME.length() + 2, dirFile.getName().indexOf(' ', MOVIE_NAME.length() + 4)).trim();
                        } catch (Throwable t) {
                            continue;
                        }
                        String[] movieDetailsArray = movieDetails.split("x");
                        int movieSeason, movieEpisode;
                        try {
                            movieSeason = Integer.parseInt(movieDetailsArray[0]);
                            movieEpisode = Integer.parseInt(movieDetailsArray[1]);
                        } catch (Exception e) {
                            System.out.println("Exception 2");
                            continue;
                        }
                        if (season == movieSeason && episode == movieEpisode) {
                            if (file.list().length == 1 && file.list()[0].endsWith(".srt")) {
                                MovieSubtitlesRename.copy(new File(file, file.list()[0]), new File(INPUT_FOLDER), dirFile.getName().substring(0, dirFile.getName().length() - ".mp4".length()) + ".srt");
                            } else if (new File(file, MOVIE_NAME + "_S" + movieSeason + "_E" + movieEpisode + "_English.srt").exists()) {
                                MovieSubtitlesRename.copy(new File(file, MOVIE_NAME + "_S" + movieSeason + "_E" + movieEpisode + "_English.srt"), new File(INPUT_FOLDER), dirFile.getName().substring(0, dirFile.getName().length() - ".mp4".length()) + ".srt");
                            }
                        }  
                    }
                }
            }
        }
        System.out.println("Done");
    }
    
    private static void copyToMain() {
        
    }
    
}
