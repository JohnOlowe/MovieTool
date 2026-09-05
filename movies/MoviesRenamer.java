package movies;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Scanner;

public class MoviesRenamer {
    public static final String PATH = MovieSubtitlesRename.INPUT_FOLDER + (MovieSubtitlesRename.INPUT_FOLDER.endsWith("/") ? "" : "/");
    public static final String TVSUBPATH = PATH + "Subtitles";
    public static final String SUB_TITLE_LIST = PATH + "titles.list";
    
    private static String[] MOVIEBOX_SUBS = {"The_Flash_S1_E1_English.srt", "American_Murderer_English.srt"};
    private static String[] TVSUBTITLES = {"The Flash 2014  - 4x06 - When Harry Met Harry….HDTV.KILLERS.en.srt", "The Flash 2014  - 9x01 - Wednesday Ever After.WEB.AMZN.en.srt", "Supergirl - 2x08 - Medusa.WEB.HBOGO.en.srt"};
    
    private static HashMap<String, SubtitleFile> movieboxSubs = new HashMap<>();
    private static HashMap<String, SubtitleFile> tvSubtitleSubs = new HashMap<>();
    private static HashMap<String, MovieFile> movieboxVids = new HashMap<>();
    
    public static void main(String[] args) {
        // testSubtitleFile();
        MovieSubtitlesRename.main(null);
        System.out.println("Started");
        if (!categorizeFiles(PATH, TVSUBPATH)) return;
        
        // int episodeNumbersSize = renameTVSubtitlesToMoviebox();
        int episodeNumbersSize = renameSubtitlesTitleToMoviebox();
        
        System.out.println("For " + episodeNumbersSize + " files, completed successfully");
    }
    
    private static int renameTVSubtitlesToMoviebox() {
        Set<String> episodeNumbers = tvSubtitleSubs.keySet();
        for (String episodeNum : episodeNumbers) {
            SubtitleFile tvSubtitleFile = tvSubtitleSubs.get(episodeNum);
            SubtitleFile movieboxSubtitleFile = movieboxSubs.get(episodeNum);
            MovieFile movieboxMovieFile = movieboxVids.get(episodeNum);

            String name = tvSubtitleFile.name;

            if (movieboxSubtitleFile != null)
                new File(PATH, movieboxSubtitleFile.name).renameTo(new File(PATH, name));
            if (movieboxMovieFile == null) {
                // Copy the subtitles file over here
                try {
                    FileInputStream tvSubFileStream = new FileInputStream(new File(TVSUBPATH, name));
                    FileOutputStream outputFile = new FileOutputStream(new File(PATH, name));
                    int read;
                    byte[] buffer = new byte[1024];
                    while ((read = tvSubFileStream.read(buffer)) > 0) {
                        outputFile.write(buffer, 0, read);
                    }
                    tvSubFileStream.close();
                    outputFile.close();
                } catch (Throwable t) {
                    System.out.println(episodeNum);
                }
            } else 
                new File(PATH, movieboxMovieFile.name).renameTo(new File(PATH, name.substring(0, name.length() - SubtitleFile.EXTENSION.length()) + MovieFile.EXTENSION[0]));
        }
        return episodeNumbers.size();
    }
    
    private static int renameSubtitlesTitleToMoviebox() {
        try {
            int count = 0;
            Scanner scanner = new Scanner(new FileInputStream(new File(SUB_TITLE_LIST)));
            while (scanner.hasNextLine()) {
                String nextTitle = scanner.nextLine().trim();
                // S4.E11 ∙ The Book of Reunification: Chapter Two: Trial and Errors
                int dotIndex = nextTitle.indexOf(" ∙ ");
                if (dotIndex == -1) {
                    continue;
                }
                String episodeIdentifierDetail = nextTitle.substring(0, dotIndex).trim();
                System.out.println(episodeIdentifierDetail);
                String[] episodeIdentifierDetails = episodeIdentifierDetail.split("\\.");

                String seasonStr = episodeIdentifierDetails[0];
                String episodeStr = episodeIdentifierDetails[1];
                
                if (episodeIdentifierDetails.length != 2 || seasonStr.length() < 2 || seasonStr.length() > 3 || episodeStr.length() < 2 || episodeStr.length() > 3) {
                    return -2;
                }
                if (seasonStr.charAt(0) != 'S' || episodeStr.charAt(0) != 'E') {
                    return -3;
                }
                int season = Integer.parseInt(seasonStr.substring(1));
                int episode = Integer.parseInt(episodeStr.substring(1));
                if (season <= 0 || episode <= 0) return -4;
                
                String title = nextTitle.substring(dotIndex + 3).replaceAll(":", "");
                
                String episodeNum = season + "x" + episode;
                SubtitleFile movieboxSubtitleFile = movieboxSubs.get(episodeNum);
                MovieFile movieboxMovieFile = movieboxVids.get(episodeNum);

                if (movieboxMovieFile == null) continue;
                
                String name = movieboxMovieFile.movieName + " - " + episodeNum + " - " + title + ".MVB.IMDB.en.srt";

                if (movieboxSubtitleFile != null)
                    new File(PATH, movieboxSubtitleFile.name).renameTo(new File(PATH, name));
                // System.out.println(name);
                // System.out.println(movieboxMovieFile.name);
                new File(PATH, movieboxMovieFile.name).renameTo(new File(PATH, name.substring(0, name.length() - SubtitleFile.EXTENSION.length()) + MovieFile.EXTENSION[0]));
                count++;
            }
            return count;
        } catch (Throwable t) {
            t.printStackTrace();
            return -100;
        }
    }
    
    private static boolean categorizeFiles(String folderPath, String tvSubPath) {
        if (folderPath.equals(tvSubPath)) return false;
        
        File[] files = new File(folderPath).listFiles();
        if (files == null) return false;
        // Movie Moviebox
        // System.out.println("Movies Downloaded from MovieBox\n");
        for (File file : files) {
            try {
                MovieFile movieFile = new MovieFile(file, MovieFile.TYPE_MOVIEBOX);
                movieboxVids.put(movieFile.season + "x" + movieFile.episode, movieFile);
            } catch (Throwable t) {
            }
        }
        
        // Subtitles Moviebox
        System.out.println("\nSubtitles from MovieBox\n");
        for (File file : files) {
            try {
                SubtitleFile subtitleFile = new SubtitleFile(file, SubtitleFile.TYPE_MOVIEBOX);
                System.out.println(file.getName());
            } catch (Throwable t) {}
        }
        
        // Renamed subtitles Moviebox
        // System.out.println("\nRenamed subtitles from MovieBox\n");
        for (File file : files) {
            try {
                SubtitleFile subtitleFile = MovieFile.parseRenamedSubtitle(file, SubtitleFile.TYPE_MOVIEBOX);
                movieboxSubs.put(subtitleFile.season + "x" + subtitleFile.episode, subtitleFile);
                // System.out.println(file.getName());
            } catch (Throwable t) {}
        }
        
        // TV subtitles
        // System.out.println("\nSubtitles from TVSubtitles\n");
        File tvSubs = new File(tvSubPath);
        tvSubs.mkdir();
        files = tvSubs.listFiles();
        if (files == null) return false;
        for (File file : files) {
            try {
                SubtitleFile subtitleFile = new SubtitleFile(file, SubtitleFile.TYPE_TVSUBTITLES);
                tvSubtitleSubs.put(subtitleFile.season + "x" + subtitleFile.episode, subtitleFile);
                // System.out.println(file.getName());
            } catch (Throwable t) {}
        }
        System.out.println("Loaded Successfully"); 
        return true;
    }

    private static void testSubtitleFile() {
        for (String moviebox : MOVIEBOX_SUBS) {
            System.out.println(new SubtitleFile(moviebox, SubtitleFile.TYPE_MOVIEBOX));
            System.out.println();
        }
        for (String tvsubtitle : TVSUBTITLES) {
            System.out.println(new SubtitleFile(tvsubtitle, SubtitleFile.TYPE_TVSUBTITLES));
            System.out.println();
        }
    }
}

