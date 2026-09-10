package movies;

import movies.core.ConventionRegistry;
import movies.core.FileNameParts;
import movies.core.NamingConvention;
import movies.core.OperationResult;
import movies.core.Options;
import movies.core.Problem;
import movies.core.RenameEngine;
import movies.core.NameSanitizer;
import movies.core.TransferAction;
import movies.ops.Flattener;
import movies.ops.HealthCheck;
import movies.ops.ImdbRename;
import movies.ops.EpisodeLister;
import movies.ops.SubsDownloader;
import movies.ops.SubsMerger;
import movies.ops.SubsRelocator;
import movies.ops.SubsShift;
import movies.ops.SubsSync;
import movies.ops.TitlesCleaner;
import movies.ops.VttConvert;
import movies.subs.SubRip;
import movies.util.IoUtil;
import movies.util.Json;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-Java test suite (no JUnit available). Run with:
 *
 *   java -cp build/test-classes movies.SelfTest
 *
 * Creates fixture libraries in the system temp folder, exercises every
 * operation and prints PASS/FAIL per check.
 */
public final class SelfTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> failures = new ArrayList<String>();
    private static final List<File> tempDirs = new ArrayList<File>();

    public static void main(String[] args) throws Exception {
        testConventions();
        testPatternBuilder();
        testSrtParsing();
        testVttParsing();
        testMerge();
        testShift();
        testSanitizer();
        testRename();
        testImdbRename();
        testSync();
        testSubsRelocate();
        testTitlesClean();
        testJson();
        testDownloadSubs();
        testFlatten();
        testEpisodesAndCheck();

        System.out.println();
        System.out.println("Passed: " + passed + "  Failed: " + failed);
        for (String failure : failures) {
            System.out.println("  FAIL " + failure);
        }
        // Best-effort cleanup of the fixture folders.
        for (File dir : tempDirs) IoUtil.deleteRecursively(dir);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------ helpers

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            failures.add(name);
            System.out.println("  FAIL " + name);
        }
    }

    private static File tempDir(String name) throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"), "movietool-test-" + name + "-" + System.nanoTime());
        if (!dir.mkdir()) throw new IOException("cannot create " + dir);
        tempDirs.add(dir);
        return dir;
    }

    private static File touch(File dir, String name) throws IOException {
        File file = new File(dir, name);
        IoUtil.writeText(file, "fixture");
        file.deleteOnExit();
        return file;
    }

    private static File mkdir(File dir, String name) {
        File child = new File(dir, name);
        child.mkdirs();
        child.deleteOnExit();
        return child;
    }

    private static Options options(String folder) {
        Options options = new Options();
        options.setFolder(folder);
        return options;
    }

    private static int planned(OperationResult result) {
        int n = 0;
        for (TransferAction t : result.getTransfers()) {
            if (t.state == TransferAction.State.PLANNED) n++;
        }
        return n;
    }

    // ------------------------------------------------------------- tests

    private static void testConventions() {
        ConventionRegistry registry = new ConventionRegistry();

        // MovieBox
        NamingConvention moviebox = registry.get("moviebox");
        FileNameParts parts = moviebox.parse("The_Flash_1080P_S01_E01.mp4");
        check("moviebox episode video", parts != null && parts.getSeason() == 1 && parts.getEpisode() == 1
                && parts.getQuality() == 1080 && parts.getTitle().equals("The_Flash"));
        parts = moviebox.parse("The_People_We_Hate_at_the_Wedding_480P.mp4");
        check("moviebox movie video", parts != null && parts.getQuality() == 480
                && parts.getTitle().equals("The_People_We_Hate_at_the_Wedding") && !parts.isEpisode());
        parts = moviebox.parse("The_Flash_S1_E1_English.srt");
        check("moviebox episode sub", parts != null && parts.isEpisode() && parts.getLanguage().equals("English"));
        parts = moviebox.parse("American_Murderer_English.srt");
        check("moviebox movie sub", parts != null && !parts.isEpisode() && parts.getLanguage().equals("English")
                && parts.getTitle().equals("American_Murderer"));
        parts = moviebox.parse("Countdown_480P_S01_E08.srt");
        check("moviebox renamed sub", parts != null && parts.isEpisode() && parts.getQuality() == 480
                && parts.getSeason() == 1 && parts.getEpisode() == 8);
        parts = moviebox.parse("Outer_Banks_S01_E01");
        check("moviebox folder", parts != null && parts.isEpisode() && parts.getTitle().equals("Outer_Banks"));
        String rebuilt = moviebox.build(parts);
        check("moviebox build round trip", rebuilt.equals("Outer_Banks_S01_E01"));

        // TvSubtitles
        NamingConvention tvsubs = registry.get("tvsubtitles");
        parts = tvsubs.parse("The Flash 2014  - 9x01 - Wednesday Ever After.WEB.AMZN.en.srt");
        check("tvsubtitles parse", parts != null && parts.getSeason() == 9 && parts.getEpisode() == 1
                && parts.getEpisodeTitle().equals("Wednesday Ever After") && parts.getLanguage().equals("en"));
        check("tvsubtitles tags kept", parts != null && parts.getTags().contains("WEB") && parts.getTags().contains("AMZN"));
        if (parts != null) {
            String out = tvsubs.build(parts);
            check("tvsubtitles build keeps tags", out.contains(".WEB.AMZN.en.srt"));
        }
        check("tvsubtitles rejects plain", tvsubs.parse("Some Movie.mp4") == null);

        // Awafim
        NamingConvention awafim = registry.get("awafim");
        parts = awafim.parse("The Flash S04E05 - Girls Night Out (Awafim.tv).mp4");
        check("awafim parse", parts != null && parts.getSeason() == 4 && parts.getEpisode() == 5
                && parts.getEpisodeTitle().equals("Girls Night Out"));
        parts = awafim.parse("The Flash S07E16 - P.O.W.mp4");
        check("awafim no-site parse", parts != null && parts.getEpisode() == 16);
        parts = awafim.parse("The Flash S06E11 - Love is a Battlefield (Awafim.tv) (1).mp4");
        check("awafim duplicate marker", parts != null && parts.getEpisode() == 11
                && parts.getTags().contains("Awafim.tv"));
        parts = awafim.parse("Outer Banks - S01E01 - Pilot.mp4");
        check("awafim reparses renamed", parts != null && parts.getTitle().equals("Outer Banks")
                && parts.getSeason() == 1 && parts.getEpisode() == 1
                && parts.getEpisodeTitle().equals("Pilot"));

        // Waploaded
        NamingConvention waploaded = registry.get("waploaded");
        parts = waploaded.parse("[Waploaded_20451]Running_the_Bases_2022.mp4");
        check("waploaded parse", parts != null && parts.getYear() == 2022
                && parts.getTitle().equalsIgnoreCase("Running the Bases"));
        parts = waploaded.parse("[Waploaded]_indivisible-2018.mp4");
        check("waploaded variant", parts != null && parts.getYear() == 2018);

        // Seriezloaded
        parts = registry.get("seriezloaded").parse("Morningside (2025) (SeriezLoaded.ng).mkv");
        check("seriezloaded parse", parts != null && parts.getYear() == 2025
                && parts.getTags().contains("SeriezLoaded.ng"));

        // FZMovies
        NamingConvention fzmovies = registry.get("fzmovies");
        parts = fzmovies.parse("Not_Easily_Broken_(2009)_BluRay_high_(fzmovies.net)_293f7995284bf0d67a542abaac5f04c6.mp4");
        check("fzmovies movie parse", parts != null && parts.getYear() == 2009
                && parts.getTitle().equalsIgnoreCase("Not Easily Broken"));
        parts = fzmovies.parse("The_Flash_-_S01E03_-_Things_You_Can_t_Outrun_03131193c1ce0a6f409da82f61a0f22a.mp4");
        check("fzmovies episode parse", parts != null && parts.getSeason() == 1 && parts.getEpisode() == 3
                && parts.getEpisodeTitle().equalsIgnoreCase("Things You Can t Outrun"));

        // NKIRI
        parts = registry.get("nkiri").parse("The.Hill.(NKIRI.COM).2023.AMZN.WEBRip.DOWNLOADED.FROM.NKIRI.COM.mkv");
        check("nkiri parse", parts != null && parts.getYear() == 2023
                && parts.getTitle().equalsIgnoreCase("The Hill"));

        // Scene
        NamingConvention scene = registry.get("scene");
        parts = scene.parse("Show.Name.S01E02.720p.WEB-DL.x264.NG.srt");
        check("scene episode parse", parts != null && parts.getSeason() == 1 && parts.getEpisode() == 2
                && parts.getQuality() == 720);
        parts = scene.parse("Some.Movie.2023.1080p.WEB-DL.mkv");
        check("scene movie parse", parts != null && parts.getYear() == 2023 && parts.getQuality() == 1080);

        // Regular
        parts = registry.get("regular").parse("Some Movie Name (2023).mp4");
        check("regular parse", parts != null && parts.getYear() == 2023
                && parts.getTitle().equals("Some Movie Name"));

        // Detection order: specific conventions must win.
        parts = registry.detect("The Flash S04E05 - Girls Night Out (Awafim.tv).mp4", registry.all());
        check("detect awafim", parts != null && parts.getConvention().equals("awafim"));
        parts = registry.detect("Completely unknown name.xyz", registry.all());
        check("detect regular fallback", parts != null && parts.getConvention().equals("regular"));
    }

    private static void testPatternBuilder() {
        ConventionRegistry registry = new ConventionRegistry();
        FileNameParts parts = registry.detect("The_Flash_1080P_S01_E01.mp4", registry.all());
        String name = RenameEngine.PatternBuilder.build("{title} {s01e01}{ext}", parts);
        check("pattern s01e01", name.equals("The_Flash S01E01.mp4"));
        name = RenameEngine.PatternBuilder.build("{s1e1} {title}{ext}", parts);
        check("pattern s1e1", name.equals("1E1 The_Flash.mp4"));
        parts = registry.detect("American_Murderer_English.srt", registry.all());
        name = RenameEngine.PatternBuilder.build("{title} (2023){ext}", parts);
        check("pattern movie", name.equals("American_Murderer (2023).srt"));
    }

    private static void testSrtParsing() {
        String srt = "1\n00:00:01,000 --> 00:00:02,500\nHello\n\n2\n00:00:03,000 --> 00:00:04,000\nSecond line\nmore\n";
        SubRip track = SubRip.parseSrt(srt);
        check("srt block count", track.cues().size() == 2);
        check("srt time", track.cues().get(0).start == 1000 && track.cues().get(0).end == 2500);
        check("srt multiline text", track.cues().get(1).text.contains("Second line\nmore"));

        String vtt = "WEBVTT\n\n1\n00:01.000 --> 00:02.000 align:start position:10%\nHi <c.colorE5E5E5>there</c>\n\nNOTE a note\n\n00:00:03.000 --> 00:00:04.000\n<v Roger>Done</v>\n";
        SubRip fromVtt = SubRip.parseVtt(vtt);
        check("vtt cue count", fromVtt.cues().size() == 2);
        check("vtt no cue settings", fromVtt.cues().get(0).start == 1000);
        check("vtt strips c tags", fromVtt.cues().get(0).text.equals("Hi there"));
        check("vtt strips v tags", fromVtt.cues().get(1).text.equals("Done"));
        check("srt format", SubRip.formatTimestamp(3723456).equals("01:02:03,456"));
    }

    private static void testVttParsing() {
        // covered in testSrtParsing; keep for numbering stability
    }

    private static void testMerge() {
        SubRip a = SubRip.parseSrt("1\n00:00:01,000 --> 00:00:03,000\nAAA\n");
        SubRip b = SubRip.parseSrt("1\n00:00:02,000 --> 00:00:04,000\nBBB\n");
        SubRip merged = SubRip.merge(a, b, false);
        check("merge overlap stacked", merged.cues().size() == 3);
        boolean foundStacked = false;
        for (SubRip.Cue cue : merged.cues()) {
            if (cue.text.contains("AAA") && cue.text.contains("BBB")) foundStacked = true;
        }
        check("merge contains stacked cue", foundStacked);
        SubRip identical = SubRip.parseSrt("1\n00:00:01,000 --> 00:00:02,000\nSAME\n\n2\n00:00:02,000 --> 00:00:03,000\nSAME\n");
        SubRip compact = SubRip.merge(identical, new SubRip(), false);
        check("merge coalesces identical", compact.cues().size() == 1);
    }

    private static void testShift() throws IOException {
        File dir = tempDir("shift");
        File sub = touch(dir, "Movie.srt");
        IoUtil.writeText(sub, "1\n00:00:10,000 --> 00:00:12,000\nText\n");
        Options options = options(sub.getAbsolutePath());
        options.setShiftSeconds(-2.5);
        OperationResult result = new SubsShift().shift(options);
        check("shift runs", result.errorCount() == 0);
        SubRip shifted = SubRip.loadSrt(sub);
        check("shift moved", shifted.cues().get(0).start == 7500);
        check("shift backup", new File(dir, "Movie.srt.bak").exists());
    }

    private static void testRename() throws IOException {
        File dir = tempDir("rename");
        touch(dir, "The_Flash_1080P_S01_E01.mp4");
        touch(dir, "The_People_We_Hate_at_the_Wedding_480P.mp4");
        touch(dir, "Random_Junk_123.mp4");

        Options options = options(dir.getAbsolutePath());
        options.setPattern("{title} {s01e01}{ext}");
        RenameEngine engine = new RenameEngine();
        RenameEngine.RenamePlan plan = engine.plan(options, "");
        check("rename plan count", plan.renameCount() == 3);
        check("rename no problems", engine.problems().isEmpty());

        engine = new RenameEngine();
        plan = engine.plan(options, "");
        check("rename replan stable", plan.renameCount() == 3);
        plan.apply();
        check("rename applied", new File(dir, "The_Flash S01E01.mp4").exists());
        check("rename removed old", !new File(dir, "The_Flash_1080P_S01_E01.mp4").exists());
        check("rename plain de-underlined", new File(dir, "Random Junk 123.mp4").exists());

        // Convention targeted rename with a collision (both tags collapse away).
        File collisionDir = tempDir("rename-collision");
        touch(collisionDir, "A (sub1).mp4");
        touch(collisionDir, "A (sub2).mp4");
        Options collisionOptions = options(collisionDir.getAbsolutePath());
        RenameEngine collisionEngine = new RenameEngine();
        RenameEngine.RenamePlan collisionPlan = collisionEngine.plan(collisionOptions, "regular");
        boolean collisionSeen = false;
        for (RenameEngine.RenameAction action : collisionPlan.actions()) {
            if (action.kind == RenameEngine.RenameAction.Kind.COLLISION) collisionSeen = true;
        }
        check("rename collision detected", collisionSeen);
        collisionPlan.apply(); // collisions are not executed
        check("rename collision kept original", new File(collisionDir, "A (sub2).mp4").exists());
    }

    private static void testSanitizer() {
        check("sanitize removes windows-illegal", NameSanitizer.sanitize("Trial: Two? Bro/E* \"X\"").equals("Trial Two BroE X"));
        check("sanitize collapses spaces", NameSanitizer.sanitize("A: B? C").equals("A B C"));
        check("sanitize trims trailing dot", NameSanitizer.sanitize("Title.").equals("Title"));
        check("sanitize keeps legal", NameSanitizer.sanitize("P.O.W - 100%").equals("P.O.W - 100%"));
        check("sanitize replacement", NameSanitizer.sanitize("What?:", "-").equals("What--"));
        check("sanitize nbsp", NameSanitizer.sanitize("A\u00A0B").equals("A B"));
    }

    private static void testImdbRename() throws IOException {
        File dir = tempDir("imdb");
        touch(dir, "Outer_Banks_S01_E01.mp4");
        touch(dir, "Outer_Banks_S01_E01_English.srt");
        touch(dir, "Outer_Banks_S01_E02.mp4");
        File subsFolder = mkdir(dir, "Subtitles");
        File ep2 = mkdir(subsFolder, "Outer_Banks_S01_E02");
        touch(ep2, "whatever.en.srt");
        IoUtil.writeText(new File(dir, "titles.list"),
                "Outer Banks (2020)\n"
                + "TV Series\n"
                + "S1.E1 \u2219 Pilot\n"
                + "S1.E2 \u2219 Middle of Nowhere: Fun Bro?\n"
                + "S1.E3 \u2219 Not downloaded yet\n");

        Options options = options(dir.getAbsolutePath());
        ImdbRename imdb = new ImdbRename();
        OperationResult result = imdb.plan(options);
        check("imdb planned count", planned(result) == 4);
        check("imdb unmatched info", !result.problems().isEmpty());

        imdb.apply(result, options);
        check("imdb video renamed (default tag)", new File(dir, "Outer Banks - S01E01 - Pilot.MVB.IMDB.en.mp4").exists());
        check("imdb sub renamed (default tag)", new File(dir, "Outer Banks - S01E01 - Pilot.MVB.IMDB.en.srt").exists());
        check("imdb illegal chars removed", new File(dir, "Outer Banks - S01E02 - Middle of Nowhere Fun Bro.MVB.IMDB.en.mp4").exists());
        check("imdb sub moved out of Subtitles", new File(dir, "Outer Banks - S01E02 - Middle of Nowhere Fun Bro.MVB.IMDB.en.srt").exists());
        check("imdb originals gone", !new File(dir, "Outer_Banks_S01_E01.mp4").exists());

        // Style and tag options on a fresh fixture.
        File dir2 = tempDir("imdb2");
        touch(dir2, "Show_1080P_S02_E05.mp4");
        IoUtil.writeText(new File(dir2, "titles.list"), "S2.E5 \u2219 Hello: World?\n");
        Options options2 = options(dir2.getAbsolutePath());
        options2.setStyle("1x01");
        options2.setTag("MVB.IMDB.en");
        ImdbRename imdb2 = new ImdbRename();
        OperationResult result2 = imdb2.plan(options2);
        imdb2.apply(result2, options2);
        check("imdb style 1x01 with tag", new File(dir2, "Show - 2x05 - Hello World.MVB.IMDB.en.mp4").exists());

        // A raw episode line that cannot parse must warn, not abort.
        File dir3 = tempDir("imdb3");
        touch(dir3, "Show_720P_S01_E01.mp4");
        IoUtil.writeText(new File(dir3, "titles.list"), "S1.E1 \u2219 Fine\n" + "S1 ∙ Broken\n" + "S1.E2 \u2219 Also fine\n");
        Options options3 = options(dir3.getAbsolutePath());
        ImdbRename imdb3 = new ImdbRename();
        OperationResult result3 = imdb3.plan(options3);
        boolean warned = false;
        for (Problem p : result3.problems()) {
            if (p.getSeverity() == Problem.Severity.WARN && p.getMessage().contains("Broken")) warned = true;
        }
        check("imdb broken line warns but continues", warned);
        imdb3.apply(result3, options3);
        check("imdb continues after bad line", new File(dir3, "Show - S01E01 - Fine.MVB.IMDB.en.mp4").exists());

        // The tag can be disabled (GUI checkbox / CLI --no-tag).
        File dir4 = tempDir("imdb4");
        touch(dir4, "Show_720P_S01_E01.mp4");
        IoUtil.writeText(new File(dir4, "titles.list"), "S1.E1 \u2219 Fine\n");
        Options options4 = options(dir4.getAbsolutePath());
        options4.setTag("");
        ImdbRename imdb4 = new ImdbRename();
        OperationResult result4 = imdb4.plan(options4);
        imdb4.apply(result4, options4);
        check("imdb tag disabled", new File(dir4, "Show - S01E01 - Fine.mp4").exists());
    }

    private static void testSubsRelocate() throws IOException {
        // Series layout: per-episode folders, loose subs, one folder already migrated.
        File dir = tempDir("relocate");
        touch(dir, "Outer_Banks_S01_E01.mp4");
        File ep1 = mkdir(dir, "Outer_Banks_S01_E01");            // pure subtitle folder
        touch(ep1, "whatever.en.srt");
        touch(dir, "Outer_Banks_S01_E02.mp4");
        File ep2 = mkdir(dir, "Outer_Banks_S01_E02");            // already migrated earlier
        touch(ep2, "Outer_Banks_S01_E02_English.srt");
        File mixed = mkdir(dir, "Movie_Night");                  // video + subtitle together
        touch(mixed, "Movie_Night.mp4");
        touch(mixed, "Movie_Night.en.srt");
        touch(dir, "stray.srt");
        touch(dir, "notes.txt");
        File subs = mkdir(dir, "Subtitles");
        File ep2migrated = mkdir(subs, "Outer_Banks_S01_E02");
        IoUtil.writeText(new File(ep2migrated, "Outer_Banks_S01_E02_English.srt"), "fixture");
        IoUtil.writeText(new File(subs, "stray.srt"), "existing");

        Options options = options(dir.getAbsolutePath());
        SubsRelocator relocator = new SubsRelocator();
        OperationResult result = relocator.plan(options);
        check("relocate planned count", planned(result) == 2);   // E01 whole + Movie_Night sub
        int skipped = 0;
        for (TransferAction t : result.getTransfers()) {
            if (t.state == TransferAction.State.SKIPPED_EXISTS) skipped++;
        }
        check("relocate skips existing", skipped == 2);          // E02 folder + stray.srt
        relocator.apply(result, options);
        check("relocate sub folder moved whole", new File(subs, "Outer_Banks_S01_E01/whatever.en.srt").exists());
        check("relocate sub folder source gone", !ep1.exists());
        check("relocate mixed folder keeps video", new File(mixed, "Movie_Night.mp4").exists());
        check("relocate mixed sub grouped by folder", new File(subs, "Movie_Night/Movie_Night.en.srt").exists());
        check("relocate mixed sub source gone", !new File(mixed, "Movie_Night.en.srt").exists());
        check("relocate folder skip keeps original", new File(ep2, "Outer_Banks_S01_E02_English.srt").exists());
        check("relocate migrated content intact", "fixture".equals(
                IoUtil.readText(new File(ep2migrated, "Outer_Banks_S01_E02_English.srt"))));
        check("relocate stray kept", new File(dir, "stray.srt").exists());
        check("relocate ignores non-subs", !new File(subs, "notes.txt").exists());

        // Recursive: a pure sub folder inside "Season 1" moves whole by its own
        // name; a mixed folder reached twice is still planned only once.
        File season = mkdir(dir, "Season 1");
        File ep3 = mkdir(season, "Outer_Banks_S01_E03");
        touch(ep3, "Outer_Banks_S01_E03_English.srt");
        File extras = mkdir(dir, "Extras_2023");
        touch(extras, "Extras_2023.mp4");
        touch(extras, "Extras_2023.en.srt");
        options.setOverwrite(true);
        options.setRecursive(true);
        OperationResult result2 = relocator.plan(options);
        check("relocate recursive planned", planned(result2) == 3);  // stray + E03 folder + extras sub
        int dupes = 0;
        for (TransferAction t : result2.getTransfers()) {
            if (t.from.getName().equals("Extras_2023.en.srt")) dupes++;
        }
        check("relocate no duplicate plans", dupes == 1);
        relocator.apply(result2, options);
        check("relocate stray overwritten", "fixture".equals(IoUtil.readText(new File(subs, "stray.srt"))));
        check("relocate deep folder moved whole", new File(subs, "Outer_Banks_S01_E03/Outer_Banks_S01_E03_English.srt").exists());
        check("relocate deep source gone", !ep3.exists());
        check("relocate extras sub grouped", new File(subs, "Extras_2023/Extras_2023.en.srt").exists());
        check("relocate extras video kept", new File(extras, "Extras_2023.mp4").exists());
        check("relocate videos untouched", new File(dir, "Outer_Banks_S01_E01.mp4").exists()
                && new File(dir, "Outer_Banks_S01_E02.mp4").exists());
    }

    private static void testSync() throws IOException {
        // Old workflow: episode folders with subs inside, videos beside them.
        File dir = tempDir("sync-folders");
        touch(dir, "Outer_Banks_S01_E01.mp4");
        touch(dir, "Outer_Banks_S01_E02.mp4");
        File folder1 = mkdir(dir, "Outer_Banks_S01_E01");
        File folder2 = mkdir(dir, "Outer_Banks_S01_E02");
        touch(folder1, "something.en.srt");
        touch(folder2, "Outer_Banks_S01_E02_English.srt");

        Options options = options(dir.getAbsolutePath());
        SubsSync sync = new SubsSync();
        OperationResult result = sync.plan(options);
        check("sync folder plan", planned(result) == 2);
        File target1 = new File(dir, "Outer_Banks_S01_E01.srt");
        check("sync target name", !target1.exists());
        sync.apply(result, options);
        check("sync copied 1", target1.exists());
        check("sync copied 2", new File(dir, "Outer_Banks_S01_E02.srt").exists());

        // Cross-convention: tvsubtitles subs folder feeding awafim videos.
        File videos = tempDir("sync-videos");
        File subs = tempDir("sync-subs");
        touch(videos, "The Flash S04E05 - Girls Night Out (Awafim.tv).mp4");
        touch(subs, "The Flash 2014  - 4x05 - Girls Night Out.HDTV.KILLERS.en.srt");
        Options cross = options(videos.getAbsolutePath());
        cross.setSecondaryFolder(subs.getAbsolutePath());
        SubsSync crossSync = new SubsSync();
        OperationResult crossResult = crossSync.plan(cross);
        check("sync cross convention", planned(crossResult) == 1);
        crossSync.apply(crossResult, cross);
        check("sync cross applied", new File(videos, "The Flash S04E05 - Girls Night Out (Awafim.tv).srt").exists());

        // Second language for the same video gets a suffixed name.
        touch(subs, "The Flash 2014  - 4x05 - Girls Night Out.WEB.HBOGO.srt");
        SubsSync secondSync = new SubsSync();
        OperationResult secondResult = secondSync.plan(cross);
        secondSync.apply(secondResult, cross);
        boolean suffixFound = new File(videos, "The Flash S04E05 - Girls Night Out (Awafim.tv) (2).srt").exists();
        check("sync second sub suffixed", suffixFound);

        // A "Subtitles" folder inside the videos folder is picked up by default.
        File withDefaults = tempDir("sync-default");
        touch(withDefaults, "The Flash S04E05 - Girls Night Out (Awafim.tv).mp4");
        File defaultSubs = mkdir(withDefaults, "Subtitles");
        touch(defaultSubs, "The Flash 2014  - 4x05 - Girls Night Out.HDTV.en.srt");
        Options defaultOptions = options(withDefaults.getAbsolutePath());
        SubsSync defaultSync = new SubsSync();
        OperationResult defaultResult = defaultSync.plan(defaultOptions);
        check("sync subtitles folder default", planned(defaultResult) == 1);
        defaultSync.apply(defaultResult, defaultOptions);
        check("sync subtitles folder applied", new File(withDefaults,
                "The Flash S04E05 - Girls Night Out (Awafim.tv).srt").exists());

        // Renaming to a convention must not leave dangling " - " when the
        // episode title is missing.
        File guardDir = tempDir("rename-guard");
        touch(guardDir, "The_Flash_1080P_S01_E01.mp4");
        Options guardOptions = options(guardDir.getAbsolutePath());
        RenameEngine guardEngine = new RenameEngine();
        RenameEngine.RenamePlan guardPlan = guardEngine.plan(guardOptions, "awafim");
        check("rename guard one action", guardPlan.renameCount() == 1);
        check("rename guard no dangling dash", !guardPlan.actions().get(0).to.getName().contains(" - ."));
        guardPlan.apply();
        check("rename guard applied", new File(guardDir, "The_Flash S01E01.mp4").exists());
    }

    private static void testTitlesClean() throws IOException {
        File dir = tempDir("titles");
        String nl = System.getProperty("line.separator");
        IoUtil.writeText(new File(dir, "titles.list"),
                "Outer Banks (2020)" + nl
                + "TV Series" + nl
                + "S1.E2 \u2219 B Side" + nl
                + "S1.E1 \u2219 A Side" + nl
                + "S1.E1 \u2219 A Side" + nl
                + "S1 \u2219 broken line" + nl);
        Options options = options(dir.getAbsolutePath());
        TitlesCleaner cleaner = new TitlesCleaner();
        OperationResult result = cleaner.plan(options);
        check("titles kept two, sorted, deduped", (nl + "S1.E1 \u2219 A Side" + nl + "S1.E2 \u2219 B Side" + nl)
                .equals(nl + result.getCleanTitlesContent()));
        check("titles default target is a new file", result.getCleanTitlesTarget().equals(new File(dir, "titles-clean.list")));
        check("titles report counts", result.getReport().contains("2 episode line(s) kept"));
        cleaner.apply(result, options);
        check("titles new file written", new File(dir, "titles-clean.list").exists());
        check("titles original intact", IoUtil.readText(new File(dir, "titles.list")).startsWith("Outer Banks"));

        // Replace mode keeps a .bak of the original.
        Options replace = options(dir.getAbsolutePath());
        replace.setTitlesInPlace(true);
        replace.setOverwrite(true);
        OperationResult result2 = cleaner.plan(replace);
        check("titles replace target", result2.getCleanTitlesTarget().equals(new File(dir, "titles.list")));
        cleaner.apply(result2, replace);
        check("titles replaced", (nl + "S1.E1 \u2219 A Side" + nl + "S1.E2 \u2219 B Side" + nl)
                .equals(nl + IoUtil.readText(new File(dir, "titles.list"))));
        check("titles bak kept", IoUtil.readText(new File(dir, "titles.list.bak")).startsWith("Outer Banks"));
    }

    private static void testJson() {
        java.util.Map<String, Object> root = Json.parseObject(
                "{\"data\":[{\"attributes\":{\"files\":[{\"file_id\":42,\"file_name\":\"a.en.srt\"}],"
                + "\"release\":\"x \\\"quoted\\\"\"},\"n\":null,\"count\":123,\"ratio\":1.5,\"flag\":true}],\"ok\":true}");
        check("json root", root.containsKey("data") && Boolean.TRUE.equals(root.get("ok")));
        @SuppressWarnings("unchecked")
        java.util.List<Object> data = (java.util.List<Object>) root.get("data");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> item = (java.util.Map<String, Object>) data.get(0);
        check("json null", item.get("n") == null);
        check("json long", Long.valueOf(123).equals(item.get("count")));
        check("json double", Double.valueOf(1.5).equals(item.get("ratio")));
        check("json boolean", Boolean.TRUE.equals(item.get("flag")));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> attributes = (java.util.Map<String, Object>) item.get("attributes");
        check("json escape", "x \"quoted\"".equals(attributes.get("release")));
        @SuppressWarnings("unchecked")
        java.util.List<Object> files = (java.util.List<Object>) attributes.get("files");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> file = (java.util.Map<String, Object>) files.get(0);
        check("json nested", Long.valueOf(42).equals(file.get("file_id")));
    }

    // ---- download-subs: a tiny local stub of the OpenSubtitles + SubDL APIs ----

    private static volatile String stubApiKey;
    private static volatile String stubAuth;
    private static volatile String stubSubdlKey;
    private static volatile boolean stubSawHashSearch;
    private static volatile boolean stubSawTitleSearch;
    private static volatile boolean stubOsHits = true;
    private static volatile boolean stubOsQuota;
    private static volatile int stubPort;

    private static void testDownloadSubs() throws IOException {
        File dir = tempDir("download");
        touch(dir, "Outer_Banks_S01_E01.mp4");      // has no subtitle
        touch(dir, "Outer_Banks_S01_E02.mp4");
        touch(dir, "Outer_Banks_S01_E02.srt");      // already covered

        final java.net.ServerSocket server = new java.net.ServerSocket(0);
        stubPort = server.getLocalPort();
        Thread listener = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        handleStub(server.accept());
                    } catch (Exception closed) {
                        return;
                    }
                }
            }
        });
        listener.setDaemon(true);
        listener.start();

        String base = "http://127.0.0.1:" + stubPort;

        // 1. OpenSubtitles hash hit, no credentials: saved next to the video.
        stubOsHits = true; stubOsQuota = false;
        stubSawHashSearch = false; stubSawTitleSearch = false;
        stubApiKey = null; stubAuth = null; stubSubdlKey = null;

        Options options = options(dir.getAbsolutePath());
        options.setApiKey("test-key-123");
        options.setSubsApiBase(base);
        SubsDownloader downloader = new SubsDownloader();

        OperationResult plan = downloader.plan(options);
        check("download plan misses one", plan.getMissingVideos() != null && plan.getMissingVideos().size() == 1);
        check("download plan is offline", planned(plan) == 0);

        downloader.apply(plan, options);
        File sub = new File(dir, "Outer_Banks_S01_E01.srt");
        check("download saved next to video", sub.exists());
        check("download unpacked gzip", IoUtil.readText(sub).contains("Hello"));
        check("download api key sent", "test-key-123".equals(stubApiKey));
        check("download searched by hash", stubSawHashSearch && !stubSawTitleSearch);
        check("download no token sent", stubAuth == null);

        // 2. With account credentials the Bearer token from /login is sent.
        stubSawHashSearch = false;
        stubAuth = null;
        touch(dir, "Outer_Banks_S01_E06.mp4");
        Options withLogin = options(dir.getAbsolutePath());
        withLogin.setApiKey("test-key-123");
        withLogin.setSubsApiBase(base);
        withLogin.setOsUser("john");
        withLogin.setOsPassword("secret");
        downloader.apply(downloader.plan(withLogin), withLogin);
        check("download login token used", "Bearer stub-token-xyz".equals(stubAuth));

        // 3. OpenSubtitles has no match -> the SubDL fallback delivers.
        stubOsHits = false;
        touch(dir, "Outer_Banks_S01_E04.mp4");
        Options withSubdl = options(dir.getAbsolutePath());
        withSubdl.setApiKey("test-key-123");
        withSubdl.setSubsApiBase(base);
        withSubdl.setSubdlApiKey("subdl-key-9");
        withSubdl.setSubdlApiBase(base);
        withSubdl.setSubdlDownloadBase(base);
        downloader.apply(downloader.plan(withSubdl), withSubdl);
        check("download subdl fallback saved", IoUtil.readText(new File(dir, "Outer_Banks_S01_E04.srt")).contains("Hello-SubDL"));
        check("download subdl key used", "subdl-key-9".equals(stubSubdlKey));

        // 4. OpenSubtitles quota exhausted (406) -> falls back instead of stopping.
        stubOsQuota = true;
        touch(dir, "Outer_Banks_S01_E05.mp4");
        Options quota = options(dir.getAbsolutePath());
        quota.setApiKey("test-key-123");
        quota.setSubsApiBase(base);
        quota.setSubdlApiKey("subdl-key-9");
        quota.setSubdlApiBase(base);
        quota.setSubdlDownloadBase(base);
        downloader.apply(downloader.plan(quota), quota);
        check("download quota fallback saved", IoUtil.readText(new File(dir, "Outer_Banks_S01_E05.srt")).contains("Hello-SubDL"));

        check("download movie hash", Long.parseLong(SubsDownloader.movieHash(new File(dir, "Outer_Banks_S01_E01.mp4"))) > 0);
        server.close();
    }

    private static void handleStub(java.net.Socket socket) throws Exception {
        java.io.InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            head.append((char) c);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
        }
        String[] lines = head.toString().split("\r\n");
        String route = lines[0].split(" ")[1];
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (lower.startsWith("api-key:")) stubApiKey = line.substring(8).trim();
            if (lower.startsWith("authorization:")) stubAuth = line.substring(14).trim();
        }
        if (route.startsWith("/api/v1/login")) {
            respond(socket, "application/json", "{\"token\":\"stub-token-xyz\"}".getBytes("UTF-8"));
        } else if (route.startsWith("/api/v1/subtitles") && (route.contains("file_name=") || route.contains("film_name="))) {
            // SubDL search.
            stubSubdlKey = queryParam(route, "api_key");
            String json = "{\"status\":true,\"subtitles\":[{\"name\":\"Outer_Banks_S01_E01.srt.zip\","
                    + "\"url\":\"/subtitle/999.zip\",\"full_season\":false,\"season\":1,\"episode\":1,"
                    + "\"unpack_files\":[{\"file_n_id\":\"f1\",\"name\":\"Outer_Banks_S01_E01_English.srt\","
                    + "\"season\":1,\"episode\":1,\"format\":\"srt\",\"url\":\"/subtitle/parent/f1\"}]}]}";
            respond(socket, "application/json", json.getBytes("UTF-8"));
        } else if (route.startsWith("/api/v1/subtitles")) {
            // OpenSubtitles search.
            boolean byHash = route.contains("moviehash=");
            if (byHash) stubSawHashSearch = true; else stubSawTitleSearch = true;
            if (stubOsQuota) {
                String body = "{\"message\":\"daily download limit reached\"}";
                byte[] out = body.getBytes("UTF-8");
                String header = "HTTP/1.1 406 Not Acceptable\r\nContent-Type: application/json"
                        + "\r\nContent-Length: " + out.length + "\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(header.getBytes("UTF-8"));
                socket.getOutputStream().write(out);
                socket.getOutputStream().flush();
                socket.close();
                return;
            }
            String json = stubOsHits
                    ? "{\"data\":[{\"attributes\":{\"files\":[{\"file_id\":42,\"file_name\":\"Outer_Banks_S01_E01_English.en.srt\"}]}}]}"
                    : "{\"data\":[]}";
            respond(socket, "application/json", json.getBytes("UTF-8"));
        } else if (route.startsWith("/api/v1/download")) {
            respond(socket, "application/json",
                    ("{\"link\":\"http://127.0.0.1:" + stubPort + "/file.en.srt.gz\",\"file_name\":\"Outer_Banks_S01_E01_English.en.srt\"}")
                            .getBytes("UTF-8"));
        } else if (route.startsWith("/file.")) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(buffer);
            gzip.write("1\n00:00:01,000 --> 00:00:02,000\nHello!\n".getBytes("UTF-8"));
            gzip.close();
            respond(socket, "application/gzip", buffer.toByteArray());
        } else if (route.startsWith("/subtitle/999.zip")) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(buffer);
            zip.putNextEntry(new java.util.zip.ZipEntry("packed.srt"));
            zip.write("1\n00:00:01,000 --> 00:00:02,000\nHello-Zip!\n".getBytes("UTF-8"));
            zip.closeEntry();
            zip.close();
            respond(socket, "application/zip", buffer.toByteArray());
        } else if (route.startsWith("/subtitle/parent/f1")) {
            respond(socket, "application/x-subrip",
                    "1\n00:00:01,000 --> 00:00:02,000\nHello-SubDL!\n".getBytes("UTF-8"));
        } else {
            respond(socket, "application/json", "{\"error\":\"unknown\"}".getBytes("UTF-8"));
        }
    }

    private static String queryParam(String route, String name) {
        int q = route.indexOf('?');
        if (q < 0) return null;
        for (String pair : route.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    private static void respond(java.net.Socket socket, String type, byte[] body) throws Exception {
        String header = "HTTP/1.1 200 OK\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(header.getBytes("UTF-8"));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
        socket.close();
    }

    private static void testFlatten() throws IOException {
        File dir = tempDir("flatten");
        File nested = mkdir(dir, "season-one");
        File deeper = mkdir(nested, "ep1");
        touch(deeper, "Ep1.mkv");
        touch(dir, "Top.mp4");

        Options options = options(dir.getAbsolutePath());
        Flattener flattener = new Flattener();
        OperationResult result = flattener.plan(options);
        check("flatten plan", planned(result) == 1);
        flattener.apply(result, options);
        check("flatten moved", new File(dir, "Ep1.mkv").exists());
        check("flatten emptied folder removed", !deeper.exists());
        check("flatten kept top", new File(dir, "Top.mp4").exists());
    }

    private static void testEpisodesAndCheck() throws IOException {
        File dir = tempDir("lister");
        touch(dir, "The Flash S04E05 - Girls Night Out (Awafim.tv).mp4");
        touch(dir, "The Flash S04E06 - When Harry Met Harry (Awafim.tv).mp4");
        touch(dir, "unparseable.nothing.mp4");

        Options options = options(dir.getAbsolutePath());
        EpisodeLister lister = new EpisodeLister();
        OperationResult listing = lister.list(options);
        check("lister finds episodes", listing.getReport().contains("E05") && listing.getReport().contains("E06"));

        options.setConventions("awafim");
        HealthCheck check = new HealthCheck();
        OperationResult report = check.check(options);
        check("check counts", report.getReport().contains("3 file(s)"));
        check("check missing subs", report.getReport().contains("without subtitle: 2"));
        boolean unrecognisedSeen = false;
        for (Problem problem : report.problems()) {
            if (problem.getMessage().contains("unparseable.nothing")) unrecognisedSeen = true;
        }
        check("check unrecognised", unrecognisedSeen);
    }

    private SelfTest() {
    }
}
