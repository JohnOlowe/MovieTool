package movies;

import java.io.File;

// Have you ever written 165 lines of code, which involves parsing and it worked well at first try?
// Well, today, 31st July, 2025 was the first time it happened to me.

public class SubtitleFile extends MediaFile {
    public static final String INVALID_SUBTITLE_TYPE = "The subtitle type is invalid and not supported";
    public static final String TYPE_MISMATCH = "The subtitle file is not of the type specified";
    public static final String NOT_SUBTITLE_FILE = "The file is not a subtitle file";
    public static final String LANGUAGE_MISMATCH = "Unsupported language: ";
    public static final String EXTENSION = ".srt";
    
    // The_Flash_S1_E1_English.srt
    // American_Murderer_English.srt
    public static final int TYPE_MOVIEBOX = 0;
    // Countdown_480P_S01_E08.srt
    public static final int TYPE_MOVIEBOX_RENAMED = 1;
    // Abigail_2024_English-ELSUBTITLE.COM-SUL_826728.srt
    public static final int TYPE_ELSUBTITLE = 2;
    // Slash.2016.720p.WEB-DL.900MB.ShAaNiG.srt
    public static final int TYPE_SUBDL = 3;
    // The Flash 2014  - 9x01 - Wednesday Ever After.WEB.AMZN.en.srt
    // The Flash 2014  - 4x06 - When Harry Met Harry….HDTV.KILLERS.en.srt
    public static final int TYPE_TVSUBTITLES = 4;
    
    private int subtitleType;
    
    public SubtitleFile(String name, int type) {
        this.name = name;
        this.subtitleType = type;
        parseSubtitle();
    }
    
    public SubtitleFile(File file, int type) {
        this(file.getName(), type);
    }
    
    protected SubtitleFile(String name, String movieName, int season, int episode, String episodeName) {
        this.name = name;
        this.movieName = movieName;
        if (season > 0 && episode > 0) {
            isSeasonEpisode = true;
            this.season = season;
            this.episode = episode;
            this.episodeName = episodeName;
        }
        subtitleType = TYPE_MOVIEBOX_RENAMED;
    }
    
    private void parseSubtitle() {
        if (!name.toLowerCase().endsWith(EXTENSION)) {
            throw new RuntimeException(NOT_SUBTITLE_FILE);
        }
        switch (subtitleType) {
            case TYPE_MOVIEBOX:
                parseMoviebox();
                break;
            case TYPE_TVSUBTITLES:
                parseTVSubtitles();
                break;
            default:
                throw new RuntimeException(INVALID_SUBTITLE_TYPE);
        }
    }

    private void parseMoviebox() {
        // The_Flash_S1_E1_English.srt
        // American_Murderer_English.srt
        String name = this.name;
        
        String[] subtitleConstituents = name.split("_");
        
        if (subtitleConstituents.length < 2) {
            throw new RuntimeException(TYPE_MISMATCH);
        }
        // Start parsing from the end
        // Get the language and extension, strip off the extension to get the language
        String languageAndExtension = subtitleConstituents[subtitleConstituents.length - 1];
        String language = languageAndExtension.substring(0, languageAndExtension.length() - EXTENSION.length());
        if (!language.equalsIgnoreCase("English")) {
            throw new RuntimeException(LANGUAGE_MISMATCH + language);
        }
        // The -1 at the end indicates the dash '_' that have been stripped off in the array
        name = name.substring(0, name.length() - language.length() - EXTENSION.length() - 1);
        
        // Next check if it is a season movie
        // For it to be a season movie, the next parsed element must start with a letter E, the one before it S, and must be parsable numbers less than 100
        // For it to be a season movie, the constituent element length must be greater than 3 (the language and extension, the episode, the season, and then the variable length of the name
        
        // Check if the length is greater than 3
        if (subtitleConstituents.length > 3) {
            // "supposed" episode and season because they haven't been confirmed to be.
            String supposedSeason = subtitleConstituents[subtitleConstituents.length - 3];
            String supposedEpisode = subtitleConstituents[subtitleConstituents.length - 2];
            if (supposedEpisode.length() >= 2 && supposedEpisode.length() <= 3 && supposedSeason.length() >= 2 && supposedSeason.length() <= 3 && supposedEpisode.charAt(0) == 'E' && supposedSeason.charAt(0) == 'S') {
                int season = 0, episode = 0;
                try {
                    // The specification says S1 and E1, but this implementation allows S01 and E01
                    season = Integer.parseInt(supposedSeason.substring(1));
                    episode = Integer.parseInt(supposedEpisode.substring(1));
                } catch (Throwable t) {
                    // Swallow the exception, they are not season and episode
                }
                if (season > 0 && episode > 0) {
                    isSeasonEpisode = true;
                    this.season = season;
                    this.episode = episode;
                    // Again, the dash is in two places
                    name = name.substring(0, name.length() - supposedSeason.length() - supposedEpisode.length() - 2);
                }
            }
        }
        // Merge the rest
        if (name.trim().length() == 0) {
            throw new RuntimeException(TYPE_MISMATCH);
        }
        movieName = name;
    }
    
    private void parseTVSubtitles() {
        // The Flash 2014  - 9x01 - Wednesday Ever After.WEB.AMZN.en.srt
        String[] subtitleConstituents = name.split(" - ");
        if (subtitleConstituents.length != 3) {
            throw new RuntimeException(TYPE_MISMATCH);
        }
        String movieName = subtitleConstituents[0].trim();
        String episodeNameAndOtherDetails = subtitleConstituents[2];
        String episodeName = episodeNameAndOtherDetails.substring(0, episodeNameAndOtherDetails.indexOf('.')).trim();
        
        if (movieName.length() == 0 || episodeName.length() == 0) {
            throw new RuntimeException(TYPE_MISMATCH);
        }
        // 09x01, 29x1, 6x2
        // The length must start from 3
        String seasonEpisodeDetails = subtitleConstituents[1].trim();
        int xIndex = seasonEpisodeDetails.indexOf('x');
        if (xIndex <= 0 || xIndex >= seasonEpisodeDetails.length() - 1) {
            throw new RuntimeException(TYPE_MISMATCH);
        }
        int season = 0, episode = 0;
        try {
            season = Integer.parseInt(seasonEpisodeDetails.substring(0, xIndex));
            episode = Integer.parseInt(seasonEpisodeDetails.substring(xIndex + 1));
        } catch (Throwable t) {
            // Will know if there was an exception, the value of the variables will be unchanged
        }
        if (season <= 0 || season >= 100 || episode <= 0 || episode >= 100) {
            throw new RuntimeException(TYPE_MISMATCH);
        }
        
        this.isSeasonEpisode = true;
        this.season = season;
        this.episode = episode;
        this.movieName = movieName;
        this.episodeName = episodeName;
    }
    
}
