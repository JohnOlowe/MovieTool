package movies;

import java.io.File;

public class RenameTVSubtitles {
    
    public static void main(String[] args) {
        File[] content = new File(MovieSubtitlesRename.INPUT_FOLDER).listFiles(); 
        
        for (File file : content) {
            file.renameTo(new File(file.getPath().replace("WebRip.ION10.pt", "MVB.IMDB.en").replace("0", "")));
        }
        System.out.println("Done");
        
    }
}
