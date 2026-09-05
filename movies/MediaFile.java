package movies;

public abstract class MediaFile {
    protected String name;
    protected String movieName;

    protected boolean isSeasonEpisode;
    protected int season;
    protected int episode;
    protected String episodeName;
    
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();

        result.append("Movie Name: ").append(movieName);
        if (isSeasonEpisode) {
            result.append("\nSeason: ").append(season).append("\n");
            result.append("Episode: ").append(episode);
            if (episodeName != null) {
                result.append("\nEpisode Name:  ").append(episodeName);
            }
        }

        return result.toString();
    }
    
}
