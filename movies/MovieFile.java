package movies;

import java.io.File;

public class MovieFile extends MediaFile {
    public static final String INVALID_MOVIE_TYPE = "The movie type is invalid and not supported";
    public static final String TYPE_MISMATCH = "The movie file is not of the type specified.";
    public static final String NOT_MOVIE_FILE = "The file is not a movie file";
    public static final String INVALID_QUALITY = "Unsupported language: ";
    public static final String[] EXTENSION = {".mp4", ".mkv"};
    public static final int EXTENSION_LENGTH = EXTENSION[0].length();
    public static final int[] SUPPORTED_QUALITY = {240, 360, 480, 720, 1080};
    
    // The_Flash_1080P_S01_E01.mp4
    // The_People_We_Hate_at_the_Wedding_480P.mp4
    public static final int TYPE_MOVIEBOX = 0;
    // The Flash S04E05 - Girls Night Out (Awafim.tv).mp4
    // The Flash S07E16 - P.O.W.mp4
    // The Flash S06E11 - Love is a Battlefield (Awafim.tv) (1).mp4
    public static final int TYPE_AWAFIM = 1;
    // [Waploaded_20451]Running_the_Bases_2022.mp4
    // [Waploaded]_indivisible-2018.mp4
    public static final int TYPE_WAPLOADED = 2;
    // Morningside (2025) (SeriezLoaded.ng).mkv
    public static final int TYPE_SERIEZLOADED = 3;
    // Not_Easily_Broken_(2009)_BluRay_high_(fzmovies.net)_293f7995284bf0d67a542abaac5f04c6.mp4
    // The_Flash_-_S01E03_-_Things_You_Can_t_Outrun_03131193c1ce0a6f409da82f61a0f22a.mp4
    public static final int TYPE_FZMOVIES = 4;
    // The.Hill.(NKIRI.COM).2023.AMZN.WEBRip.DOWNLOADED.FROM.NKIRI.COM.mkv
    public static final int TYPE_NKIRI = 5;
    // Supergirl - S01E04 (TvShows4Mobile.Com)_1 otv-odme9.mp4
    // Supergirl - S01E18 (TvShows4Mobile.Com).mp4
    public static final int TYPE_TVSHOWSFORMOBILE = 6;
    
    // In regular, only the video extension isn't part of the name
    public static final int TYPE_REGULAR = -1;
    
    private int movieType;
    private int videoQuality;

    public MovieFile(String name, int type) {
        this.name = name;
        this.movieType = type;
        parseSubtitle();
    }

    public MovieFile(File file, int type) {
        this(file.getName(), type);
    }

    private void parseSubtitle() {
        boolean isMovieFile = false;
        for (String extension : EXTENSION) {
            if (name.toLowerCase().endsWith(extension)) {
                isMovieFile = true;
                break;
            }
        }
        if (!isMovieFile) {
            throw new RuntimeException(NOT_MOVIE_FILE);
        }
        switch (movieType) {
            case TYPE_MOVIEBOX:
                parseMoviebox();
                break;
            case TYPE_FZMOVIES:
                parseFZMovies();
                break;
            case TYPE_REGULAR:
                parseRegular();
                break;
            default:
                throw new RuntimeException(INVALID_MOVIE_TYPE);
        }
    }

    private void parseRegular() {
        if ((name = name.substring(0, name.length() - EXTENSION_LENGTH).trim()).length() == 0) {
            throw new RuntimeException(NOT_MOVIE_FILE);
        }
        movieName = name;
    }

    private void parseMoviebox() {
        // The_Flash_1080P_S01_E01.mp4
        // The_People_We_Hate_at_the_Wedding_480P.mp4
        
        String name = this.name;
        // Strip off the extension
        name = name.substring(0, name.length() - EXTENSION_LENGTH);
        String[] movieNameConstituents = name.split("_");
        // Check if it is a season movie
        if (movieNameConstituents.length > 3) {
            String supposedEpisode = movieNameConstituents[movieNameConstituents.length - 1];
            String supposedSeason = movieNameConstituents[movieNameConstituents.length - 2];
            if (supposedEpisode.length() >= 2 && supposedEpisode.length() <= 3 && supposedSeason.length() >= 2 && supposedSeason.length() <= 3 && supposedEpisode.charAt(0) == 'E' && supposedSeason.charAt(0) == 'S') {
                int season = 0, episode = 0;
                try {
                    // The specification says S01 and E01, but this implementation allows S1 and E1
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
        
        // Now take a look at the video quality
        
        int qualityPosition = movieNameConstituents.length - (isSeasonEpisode ? 3 : 1);
        if (qualityPosition <= 0) {
            // The movie is not valid, it needs to have both a quality and a title
            throw new RuntimeException(TYPE_MISMATCH);
        }
        String qualityStr = movieNameConstituents[qualityPosition];
        if (!qualityStr.endsWith("P") || qualityStr.length() < 4 || qualityStr.length() > 5) {
            throw new RuntimeException(TYPE_MISMATCH);
        }
        try {
            int quality = Integer.parseInt(qualityStr.substring(0, qualityStr.length() - 1));
            
            for (int valid : SUPPORTED_QUALITY) {
                if (quality == valid) {
                    this.videoQuality = quality;
                    break;
                }
            }
            
        } catch (Throwable t) {
            
        }
        if (videoQuality == 0)
            throw new RuntimeException(TYPE_MISMATCH);
        name = name.substring(0, name.length() - qualityStr.length() - 1 /*The extra dash*/);
        
        // The rest should be the movie title
        movieName = name;
    }

    private void parseFZMovies() {
        // Not_Easily_Broken_(2009)_BluRay_high_(fzmovies.net)_293f7995284bf0d67a542abaac5f04c6.mp4
        // The_Flash_-_S01E03_-_Things_You_Can_t_Outrun_03131193c1ce0a6f409da82f61a0f22a.mp4
    }
    
    public SubtitleFile getMatchingSubtitleFile() {
        return new SubtitleFile(name.substring(0, name.length() - EXTENSION_LENGTH) + SubtitleFile.EXTENSION, movieName, season, episode, episodeName);
    }
    
    public static SubtitleFile parseRenamedSubtitle(File file, int type) {
        return parseRenamedSubtitle(file.getName(), type);
    }
    
    public static SubtitleFile parseRenamedSubtitle(String name, int type) {
        if (!name.endsWith(SubtitleFile.EXTENSION)) {
            throw new RuntimeException(SubtitleFile.NOT_SUBTITLE_FILE);
        }
        MovieFile file = new MovieFile(name.substring(0, name.length() - SubtitleFile.EXTENSION.length()) + EXTENSION[0], type);
        return file.getMatchingSubtitleFile();
    }
    
}
