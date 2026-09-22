package movies.gui;

/**
 * The text of the GUI's Help section: what every function does, when to use
 * it and how they work together. Kept as one place so the guide cannot drift
 * from the card list.
 */
final class HelpBook {

    private HelpBook() {
    }

    static String html() {
        StringBuilder sb = new StringBuilder(14000);
        sb.append("<html><body style=\"width:100%;font-size:11pt;padding:8px;\">");

        sb.append("<h2>MovieTool - the guide</h2>");
        sb.append("<p>MovieTool organises movie and episode files and their subtitles. "
                + "Pick a function on the left; its form appears here; press <b>Run</b> to preview "
                + "and <b>Apply</b> to carry out the preview.</p>");

        concepts(sb);

        sb.append("<h3>The functions</h3>");
        rename(sb);
        imdb(sb);
        cleanTitles(sb);
        sync(sb);
        collect(sb);
        download(sb);
        flatten(sb);
        merge(sb);
        vtt(sb);
        shift(sb);
        episodes(sb);
        check(sb);

        workflows(sb);
        troubleshooting(sb);

        sb.append("</body></html>");
        return sb.toString();
    }

    private static void concepts(StringBuilder sb) {
        sb.append("<h3>Three ideas everything is built on</h3><ul>");
        sb.append("<li><b>Preview first, change later.</b> Every function that touches your files "
                + "shows a plan first. Nothing is modified until you press <b>Apply</b> (or tick "
                + "'Dry run' off in functions that ask). Collisions are reported, never silently "
                + "overwritten.</li>");
        sb.append("<li><b>The Subtitles folder.</b> A folder called <font face=\"monospace\">Subtitles</font> "
                + "inside your videos folder is the home of subtitle files. Downloads, syncing and "
                + "renaming all understand it - episode recognition even works from the episode "
                + "folder names inside it.</li>");
        sb.append("<li><b>Conventions.</b> MovieTool recognises file names from many sites "
                + "(MovieBox, NKIRI, FzMovies, Awafim, TVSubtitles, scene releases and more). "
                + "'movietool conventions' lists them; auto-detection tries all of them, so "
                + "mixed libraries usually just work.</li></ul>");
    }

    private static void rename(StringBuilder sb) {
        sb.append("<h4>Rename</h4><p>Renames videos <i>and</i> subtitles to one clean convention "
                + "or to your own pattern. Auto-detection picks each file's site format and the "
                + "target cleans it up; subtitles are renamed to match their video, which is what "
                + "players expect.</p>");
        sb.append("<p><b>Custom pattern tokens</b> (case-insensitive; leave out what you do not "
                + "want - empty tokens and their spaces are cleaned up automatically):</p>"
                + "<table cellpadding=\"3\">"
                + "<tr><td><font face=\"monospace\">{title}</font></td><td>show or movie title</td>"
                + "<td><font face=\"monospace\">{year}</font></td><td>release year</td></tr>"
                + "<tr><td><font face=\"monospace\">{s01e01}</font></td><td>episode as S01E02</td>"
                + "<td><font face=\"monospace\">{1x01}</font></td><td>episode as 1x02</td></tr>"
                + "<tr><td><font face=\"monospace\">{s1e1}</font></td><td>episode as 1E2</td>"
                + "<td><font face=\"monospace\">{season}</font> / <font face=\"monospace\">{episode}</font></td><td>numbers alone (1 / 2)</td></tr>"
                + "<tr><td><font face=\"monospace\">{episodeTitle}</font></td><td>episode title when the source name carries one</td>"
                + "<td><font face=\"monospace\">{quality}</font></td><td>e.g. 1080P</td></tr>"
                + "<tr><td><font face=\"monospace\">{language}</font></td><td>language token of subtitles</td>"
                + "<td><font face=\"monospace\">{ext}</font></td><td>the file's extension</td></tr>"
                + "<tr><td><font face=\"monospace\">{original}</font></td><td>original name without extension</td>"
                + "<td></td><td></td></tr></table>"
                + "<p>Example: <font face=\"monospace\">{title} - {1x01} - {episodeTitle}{ext}</font> gives "
                + "<font face=\"monospace\">The Flash - 1x02 - Fastest Man Alive.mp4</font>. A token that does not "
                + "apply (like <font face=\"monospace\">{s01e01}</font> on a movie) becomes empty, so one pattern "
                + "works for mixed folders.</p>");
    }

    private static void imdb(StringBuilder sb) {
        sb.append("<h4>IMDB rename</h4><p>Names episodes by their real titles. Copy the episode "
                + "list from IMDB or Netflix into <font face=\"monospace\">titles.list</font> in the "
                + "show's folder (lines like <font face=\"monospace\">S1.E2 &middot; Episode Title</font>), "
                + "then run this. Every episode video and its subtitle become e.g. "
                + "<font face=\"monospace\">Outer Banks - S01E02 - Middle of Nowhere.MVB.IMDB.en.mp4</font>. "
                + "The <font face=\"monospace\">MVB.IMDB.en</font> tag is kept from the original "
                + "MoviesRenamer - untick 'Append tag' for tag-free names. Characters Windows "
                + "forbids (: ? \" ...) in titles are cleaned automatically.</p>");
    }

    private static void cleanTitles(StringBuilder sb) {
        sb.append("<h4>Clean titles list</h4><p>Strips <font face=\"monospace\">titles.list</font> down "
                + "to the episode lines only: the show name, 'TV Series', season overviews and other "
                + "copied noise are removed; what remains is rewritten as clean "
                + "<font face=\"monospace\">S1.E2 &middot; Title</font> lines, deduplicated and sorted by "
                + "season/episode. By default a NEW file (<font face=\"monospace\">-clean.list</font>) is "
                + "written next to the original; tick 'Replace the original' to overwrite it instead "
                + "(a .bak copy is kept either way). Useful before an IMDB rename, or to slim a list "
                + "down for reading.</p>");
    }

    private static void sync(StringBuilder sb) {
        sb.append("<h4>Sync subtitles</h4><p>The classic job: matches subtitle files to their videos "
                + "(episode-aware, across different naming conventions) and puts each subtitle next to "
                + "its video, named exactly like the video. Subtitles inside per-episode folders or the "
                + "Subtitles folder are found too. Copy or move; a second subtitle for the same video is "
                + "disambiguated by language.</p>");
    }

    private static void collect(StringBuilder sb) {
        sb.append("<h4>Collect subtitles</h4><p>The reverse of Sync: gathers subtitles that sit "
                + "<i>outside</i> the <font face=\"monospace\">Subtitles</font> folder into it, keeping "
                + "names. Loose files move in directly; a per-episode folder that contains only "
                + "subtitles (e.g. <font face=\"monospace\">Outer_Banks_S01_E01/whatever.srt</font>) moves "
                + "<b>whole</b> - the subtitle stays inside its respective folder. Folders that also "
                + "contain a video keep the video and just donate their subtitles under "
                + "<font face=\"monospace\">Subtitles/&lt;folder name&gt;/</font>. Everything already in "
                + "Subtitles is left alone, so it is safe to run repeatedly.</p>");
    }

    private static void download(StringBuilder sb) {
        sb.append("<h4>Download subtitles</h4><p>For series or movies that came without subtitles: "
                + "press <b>Run</b> to see which videos have no subtitle, then <b>Apply</b> to fetch them "
                + "in bulk and name each one exactly like its video "
                + "(<font face=\"monospace\">Outer_Banks_S01_E01.mp4</font> &rarr; "
                + "<font face=\"monospace\">Outer_Banks_S01_E01.srt</font>), next to the video or in the "
                + "Subtitles folder. Matching is by the file's unique hash first (exact release even for "
                + "gibberish names), then by title with season/episode. "
                + "<b>Keys and free daily quotas:</b> OpenSubtitles.com - register, profile &rarr; "
                + "API Consumers &rarr; create a key: 5 downloads/day with the key alone, <b>20/day</b> "
                + "if you also enter your OpenSubtitles username + password; VIP (~$10/yr) raises it to "
                + "1000/day. <b>SubDL</b> (subdl.com &rarr; panel &rarr; API) is used automatically as a "
                + "fallback when OpenSubtitles finds nothing or is out of quota: a free key adds "
                + "<b>50 downloads/day</b>. Keys can be remembered on this computer (never the password).</p>");
    }

    private static void flatten(StringBuilder sb) {
        sb.append("<h4>Flatten</h4><p>Pulls media files out of nested folders into one folder "
                + "(renaming on name collisions) and deletes the folders it emptied. Handy after a "
                + "batch download that created one folder per episode.</p>");
    }

    private static void merge(StringBuilder sb) {
        sb.append("<h4>Merge subtitles</h4><p>Stacks two or more SRT files into one - two languages, "
                + "or SDH + dialogue. Overlapping cues are swept and coalesced; choose which track "
                + "goes on top.</p>");
    }

    private static void vtt(StringBuilder sb) {
        sb.append("<h4>VTT to SRT</h4><p>Batch-converts WebVTT subtitles (YouTube and friends) to "
                + "the universally supported SubRip format, stripping cue settings and markup while "
                + "keeping italics/bold/underline.</p>");
    }

    private static void shift(StringBuilder sb) {
        sb.append("<h4>Shift timing</h4><p>Delays or advances subtitles by a constant amount when "
                + "they are consistently out of sync (positive seconds = later, negative = earlier). "
                + "Hand-pick ANY number of files and folders in the list - one per line (the "
                + "'Add files...' button lets you ctrl-click several at once) - and they are all "
                + "shifted in ONE run. Lines without their own time use the 'Shift by (seconds)' "
                + "spinner, so those move together; give any line its own independent time by "
                + "appending it after a bar: <font face=\"monospace\">D:\\subs\\ep04.srt | 3.5</font>. "
                + "A folder line shifts every .srt inside it ('Include sub-folders' reaches deeper), "
                + "so batch-shifting a whole season is still one line. A one-time .bak of each "
                + "original is kept and never overwritten - shifting the same files twice cannot "
                + "destroy the originals.</p>");
    }

    private static void episodes(StringBuilder sb) {
        sb.append("<h4>Episodes</h4><p>Lists every recognised episode grouped per show and season "
                + "(text or CSV) - a quick inventory of what you have.</p>");
    }

    private static void check(StringBuilder sb) {
        sb.append("<h4>Library check</h4><p>A health report: which conventions your names use, "
                + "files it could not recognise, duplicate episodes, and episodes missing subtitles. "
                + "Run it first on a new library to see what needs attention.</p>");
    }

    private static void workflows(StringBuilder sb) {
        sb.append("<h3>Typical workflows</h3><ul>");
        sb.append("<li><b>A freshly downloaded series season:</b> Collect subtitles &rarr; IMDB rename "
                + "(with titles.list) &rarr; Download subtitles for the episodes that had none.</li>");
        sb.append("<li><b>An old library, subtitles mixed everywhere:</b> Library check &rarr; Collect "
                + "subtitles &rarr; Sync subtitles (if you also want copies next to the videos for "
                + "players) &rarr; Rename.</li>");
        sb.append("<li><b>One movie night file:</b> Flatten &rarr; Download subtitles &rarr; done - "
                + "the .srt sits next to the .mp4 with the same name.</li></ul>");
    }

    private static void troubleshooting(StringBuilder sb) {
        sb.append("<h3>Good to know</h3><ul>");
        sb.append("<li>The <b>Plan / results</b> tab shows the pending changes as rows; the "
                + "<b>Log</b> tab explains every decision, skip and warning.</li>");
        sb.append("<li>Files are never overwritten silently - existing targets are skipped and "
                + "reported until you allow overwriting.</li>");
        sb.append("<li>Renaming pairs videos and subtitles, so a renamed episode drags its subtitle "
                + "along and both keep matching names.</li>");
        sb.append("<li>On the command line every function has the same name as here: "
                + "<font face=\"monospace\">movietool help &lt;function&gt;</font> shows its options.</li>");
        sb.append("<li><b>Touchpad scrolling:</b> smooth two-finger scrolling in the Help and Log "
                + "panes needs a current Java runtime (Java 11 or newer, e.g. Eclipse Temurin). "
                + "On old Java 8 runtimes use the scroll bar on the right; the mouse wheel always "
                + "works.</li>");
        sb.append("</ul>");
    }
}
