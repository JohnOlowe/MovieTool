# MovieTool

A flexible Java toolkit for organising movie and episode files and their
subtitles — with a graphical interface and a full command line, on Windows,
Linux and macOS.

MovieTool grew out of a set of one-off, hard-coded rename scripts. Version 2
turns everything into one configurable tool: no more editing source files and
recompiling just to point the tool at a different folder.

```
 ┌────────────────────────────────────────────────────────────────┐
 │  MovieTool 2.0.0                                               │
 ├──────────┬─────────────────────────────────────────────────────┤
 │ Rename   │  Rename                                            │
 │ Sync     │  ┌───────────────────────────────────────────┐     │
 │ Flatten  │  │ Folder:  [D:\Shows\The Flash   ] [Browse] │     │
 │ Merge    │  │ Rename to: [moviebox  Title_1080P_S01...] │     │
 │ VTT→SRT  │  │ [x] Include sub-folders                   │     │
 │ Shift    │  │ [x] Dry run (preview only)                │     │
 │ Episodes │  └───────────────────────────────────────────┘     │
 │ Check    ├─────────────────────────────────────────────────────┤
 │          │  Plan / results            │  Log                   │
 └──────────┴─────────────────────────────────────────────────────┘
```

## Features

| Operation | What it does |
|---|---|
| **Rename** | Rename videos and subtitles to any supported naming convention, or to a custom pattern. Previews everything (dry run) and can then apply the plan atomically — even swap-style renames are safe. |
| **Sync subtitles** | The tool's core job: match subtitle files to their videos (episode-aware, even across different naming conventions) and put each subtitle next to its video, named exactly like the video. Subtitles living inside per-episode download folders are found too. Copy or move, with overwrite control. |
| **Flatten** | Pull media files out of nested folders into one folder, renaming on collisions and cleaning up emptied folders. |
| **Merge subtitles** | Stack two or more SRT tracks into one (e.g. two languages, or SDH + dialogue). Overlaps are swept and coalesced. |
| **VTT → SRT** | Batch-convert WebVTT subtitles to SubRip, stripping cue settings and `<c>` tags while keeping `<i>/<b>/<u>`. |
| **Shift timing** | Move every cue earlier or later by a constant amount, with a `.bak` backup. |
| **Episodes** | List every recognised episode grouped per show and season (text or CSV). |
| **Library check** | Health report: conventions in use, unrecognised names, duplicate episodes, episodes missing subtitles. |

## Quick start

You need **Java 8 or newer** ([Adoptium](https://adoptium.net) if you do not
have it). A pre-built `movietool.jar` ships in this repository.

### Windows

- Double-click `movietool.bat` — the graphical interface opens.
- Command line:
  ```bat
  movietool.bat help
  movietool.bat check -d "D:\Shows\The Flash" -r
  ```

### Linux / macOS

```bash
./movietool.sh              # graphical interface
./movietool.sh help         # command line
./movietool.sh check -d ~/Shows/TheFlash -r
```

If `movietool.jar` is missing, both launchers try to build it automatically
(needs a JDK; a JRE is enough to run).

## Building from source

```bash
# Windows
build.bat

# Linux / macOS
./build.sh
```

Both scripts compile with `-source 8 -target 8` (so the jar runs everywhere)
and produce `movietool.jar` in the project root. A self-test suite runs
automatically afterwards; set `SKIP_TESTS=1` to skip it.

## Naming conventions

MovieTool recognises these release-name styles out of the box (ids for
`-t/--to` and `-c/--conventions`):

| Id | Style | Example |
|---|---|---|
| `tvsubtitles` | `Show - NxNN - Title.rest.lang.srt` | `The Flash 2014 - 9x01 - Wednesday Ever After.WEB.AMZN.en.srt` |
| `awafim` | `Show SxxExx - Title (Site)` | `The Flash S04E05 - Girls Night Out (Awafim.tv).mp4` |
| `waploaded` | `[Site]Title_Year` | `[Waploaded_20451]Running_the_Bases_2022.mp4` |
| `seriezloaded` | `Title (Year) (Site)` | `Morningside (2025) (SeriezLoaded.ng).mkv` |
| `fzmovies` | `Title_(Year)_tags_(site)_hash` | `Not_Easily_Broken_(2009)_BluRay_high_(fzmovies.net)_293f...mp4` |
| `nkiri` | `Title.(Site).Year.tags` | `The.Hill.(NKIRI.COM).2023.AMZN.WEBRip...mkv` |
| `moviebox` | `Title_Quality_Sxx_Exx(+lang)` | `The_Flash_1080P_S01_E01.mp4`, `American_Murderer_English.srt` |
| `scene` | `Show.Name.SxxExx.720p...` | `Show.Name.S01E02.720p.WEB-DL.x264.NG.srt` |
| `regular` | anything plain | `Some Movie Name (2023).mp4` |

Detection is automatic and ordered most-specific first; you can restrict it to
a comma separated list of ids. Custom patterns support the tokens
`{title} {year} {s01e01} {s1e1} {season} {episode} {episodeTitle} {quality}
{language} {ext} {original}`, e.g. `"{title} {s01e01}{ext}"`.

## Command line reference

Every command that writes to your library is a **dry run by default** — add
`--apply` (`-a`) to execute. Global options: `-v/--verbose`, `-q/--quiet`.

```
movietool rename -d <folder> -t <convention> [-r] [-c ids] [--pattern p] [--apply]
movietool sync-subs -d <videos-folder> [-s <subs-folder>] [-r] [--move] [--overwrite] [--apply]
movietool flatten -d <folder> [-o <target>] [--apply]
movietool merge-subs <first.srt> <second.srt> [more...] [-o out.srt] [--top]
movietool convert-vtt <file-or-folder> [-o out] [-r] [--overwrite]
movietool shift-subs <file.srt> --seconds 2.5 [-o out] [--no-backup]
movietool list-episodes -d <folder> [-r] [--csv] [-o out.csv]
movietool check -d <folder> [-r] [-c ids]
movietool conventions          # list convention ids and examples
movietool help [command]
```

### Typical workflows

The original hard-coded workflow, now in one command each:

```bash
# 1. Subtitles arrived inside "Show_S01_E01/" folders next to the videos:
movietool sync-subs -d "Outer Banks" -v          # preview, then:
movietool sync-subs -d "Outer Banks" --apply     # copies each sub beside its episode

# 2. Everything into one naming convention (adds _English to moviebox subs):
movietool rename -d "Outer Banks" -t moviebox --apply

# 3. A mixed library: check what you have before touching anything
movietool check -d /media/library -r

# 4. Subtitles downloaded elsewhere, different naming site, matched by episode:
movietool sync-subs -d ~/Shows/Flash -s ~/Downloads/subs --move --apply
```

## Safety model

- `rename`, `sync-subs` and `flatten` are dry runs unless `--apply` is given.
- Renames are executed in two phases (everything to temporary names first),
  so cycles like `A → B` while `B → A` cannot destroy files; failures roll
  back what was already done.
- Targets that already exist are skipped (or reported as collisions) unless
  `--overwrite` is given.
- The GUI shows the exact same plan table and gets its **Apply** button
  enabled only after a successful preview.

## Migrating from the old scripts

| Old class (hard-coded) | New command |
|---|---|
| `MovieSubtitlesRename`, `CopySubs` | `sync-subs` |
| `MoviesRenamer`, `RenameTVSubtitles`, `MovieFile`/`SubtitleFile` parsing | `rename` (+ `conventions`, `-c`) |
| `MergeSubtitles` | `merge-subs` |
| `VttToSrtConverter` | `convert-vtt` |

No paths are compiled in any more — every folder, convention and option is a
parameter, and the same operations power the CLI and the GUI.

## Project layout

```
src/movies/            Java 8 sources
  core/                options, naming conventions, rename engine
  ops/                 the eight operations (shared by CLI and GUI)
  subs/                SubRip/VTT model and conversion
  cli/                 argument parser and commands
  gui/                 Swing interface
  util/                I/O helpers
test/movies/           self-test suite (plain Java, no JUnit needed)
movietool.jar          pre-built runnable jar (Main-Class: movies.Main)
build.sh / build.bat   build scripts
movietool.sh / movietool.bat  launchers
```

## Troubleshooting

- **"no Java runtime found"** — install Java 8+ or set `JAVA_HOME`.
- **"No display is available"** — you started the GUI on a headless machine;
  use the command line (`movietool help`).
- **Names reported as unrecognised** — run `movietool conventions` and try
  restricting detection with `-c`, or rename with a `--pattern`.
- **Accents/international characters look wrong** — the tool always reads and
  writes UTF-8; the launchers set `-Dfile.encoding=UTF-8` for you.
