# MovieTool

A flexible Java toolkit for organising movie and episode files and their
subtitles — with a graphical interface and a full command line, on Windows,
Linux and macOS.

MovieTool grew out of a set of one-off, hard-coded rename scripts. Version 2
turns everything into one configurable tool: no more editing source files and
recompiling just to point the tool at a different folder.

```
 ┌────────────────────────────────────────────────────────────────┐
 │  MovieTool 2.1.0                                               │
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
| **IMDB rename** | Read a titles list (`titles.list` with lines like `S1.E2 ∙ Episode Title`, as copied from IMDB or Netflix) and rename every episode — video **and** subtitle — to `Show - S01E02 - Episode Title`. Titles containing characters Windows forbids (`? : " / \ | < > *`) are sanitised automatically; the old script failed on exactly those. |
| **Sync subtitles** | The tool's core job: match subtitle files to their videos (episode-aware, even across different naming conventions) and put each subtitle next to its video, named exactly like the video. Subtitles living inside per-episode download folders are found too. A `Subtitles` folder inside the videos folder is picked up automatically. Copy or move, with overwrite control. |
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
movietool imdb-rename -d <folder> [--titles <file>] [-s <subs>] [--show name] [--style s01e01|1x01]
                     [--tag MVB.IMDB.en] [--replace-with c] [-r] [--overwrite] [--apply]
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

# 5. The IMDB workflow: titles.list next to the episodes, subtitles in "Subtitles/":
#    titles.list contains lines like:  S1.E2 ∙ Middle of Nowhere: Fun Bro?
movietool imdb-rename -d ~/Shows/Outer\ Banks -v           # preview
movietool imdb-rename -d ~/Shows/Outer\ Banks --apply      # renames videos + subtitles
#    ...or with the old tag style:  --style 1x01 --tag MVB.IMDB.en
```

## Safety model

- `rename`, `sync-subs` and `flatten` are dry runs unless `--apply` is given.
- Renames are executed in two phases (everything to temporary names first),
  so cycles like `A → B` while `B → A` cannot destroy files; failures roll
  back what was already done.
- Targets that already exist are skipped (or reported as collisions) unless
  `--overwrite` is given.
- Every name the tool generates is sanitised: characters Windows forbids
  (`? : " / \ | < > *`, control characters, trailing dots/spaces) are removed
  (or replaced via `--replace-with`), so a title like "Fun Bro?" cannot make a
  rename fail.
- The GUI shows the exact same plan table and gets its **Apply** button
  enabled only after a successful preview.

## Migrating from the old scripts

| Old class (hard-coded) | New command |
|---|---|
| `MovieSubtitlesRename`, `CopySubs` | `sync-subs` |
| `MoviesRenamer` (titles.list / IMDB part) | `imdb-rename` |
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

## Running on Android (Termux + termux-x11)

The phone setup this project was originally written on: Termux, the
`termux-x11` app, and an Arch Linux proot distro running XFCE4. Two helper
scripts live in `scripts/`:

- **`termux-session.sh`** - the fixed launcher. Differences from the old
  hand-rolled script: `PULSE_SERVER`, `DISPLAY` and `XDG_RUNTIME_DIR` are
  passed *inside* the `su -` command (a login shell wipes the environment,
  which was why the desktop had no sound), the PulseAudio TCP module is
  loaded at daemon start instead of racing `pacmd`, the OpenSL ES playback
  sink is verified, `onboard` starts inside the session, the X socket is
  awaited instead of a blind `sleep`, and a wake lock keeps audio alive in
  the background.

  Audio path: `proot app -> PulseAudio TCP 127.0.0.1:4713 -> Termux
  PulseAudio -> OpenSL ES -> Android audio stack -> speaker / headphones /
  Bluetooth` - Android itself picks whichever hardware is connected, so no
  extra binding is needed.

- **`proot-audio-bridge.sh`** - the PortAudio fix, run as root inside the
  distro (see below).
- **`proot-desktop-fix.sh`** - the XFCE/GTK crash fix (see below).
- **`proot-lib.sh`** - shared helpers (pacman with full-upgrade + mirror
  repair) used by both fix scripts.
- **`termux-audio-test.sh`** - run it in a terminal inside the XFCE session;
  it checks the whole path layer by layer (server, sinks, ALSA bridge, a
  3 second microphone round trip) and plays the recording back.

### XFCE aborts with "Gtk:ERROR ... image-missing.svg ... Bail out!"

gdk-pixbuf 2.44+ / GTK load ALL images through the sandboxed glycin loaders,
which shell out to bubblewrap `--unshare-all`. proot has no Linux namespaces
(and older proot no mount emulation), so bwrap dies and GTK treats one failed
icon as fatal - the desktop never comes up. Note: on 2.44+ there are no
classic loaders to fall back to, so hiding glycin ("No image loaders are
configured") or installing librsvg does NOT help.

Fix (one-time):

```bash
AUTO_FIX_DESKTOP=1 ./scripts/termux-session.sh
```

It restores/keeps the glycin configs, probes bubblewrap and, while it is
broken, installs a bwrap shim at `/usr/local/bin/bwrap` that execs the image
loaders directly (unsandboxed - inside proot the sandbox adds nothing; the
whole distro is a chroot). The real bwrap stays untouched at /usr/bin/bwrap,
and once `pkg upgrade proot` lands the namespace fix (termux/proot#359), the
next run detects native bwrap working and removes the shim automatically.
The session is also wrapped in dbus-launch with a private 0700
XDG_RUNTIME_DIR (Arch compiles out D-Bus autolaunch, and dbus rejects a
world-writable /tmp).

### PortAudio apps show no devices ("Error recording 0 Success")

Apps that use PortAudio (Audacity's bundled V19.7.0-devel, and most Linux
recording apps) talk to ALSA on Linux - the release has no PulseAudio
backend. Inside proot there are no ALSA cards at all (Android does not
expose `/dev/snd` to apps), so the device lists come back empty and the app
fails with the text of error code 0 - `paNoError`, printed as "Success" -
which really means "no usable device found".

The fix routes ALSA's default device to the PulseAudio server in Termux
(ALSA -> libpulse -> TCP -> OpenSL ES -> phone hardware):

```bash
AUTO_FIX=1 ./scripts/termux-session.sh    # run once; installs the audio
                                          # AND desktop fixes in one go
```

After that PortAudio lists the `default` and `pulse` devices for both
playback and recording - pick those in the app (in Audacity: Audio Host
ALSA, devices `default`/`pulse`). Every later session start re-verifies the
bridge automatically. If pacman reports 404s or "conflicting files"
(libgcc/gcc-libs), the bridge script repairs the mirrors (your distro is
Arch Linux ARM - mainline x86_64 mirrors 404 on its repos) and installs
with a full `-Syu` upgrade in the same transaction; partial upgrades are
what cause the libgcc conflict. The microphone additionally needs
`pactl load-module module-sles-source` on the Termux side (the launcher does
it and also sets the mic - not the output monitor - as the default source)
and Android's microphone permission for Termux; Android 12+ users may
also need the phantom process killer exemption noted in the launcher. If an
app runs in Termux itself (outside the proot distro) it has the same empty
PortAudio problem - run it inside the XFCE session instead.

MovieTool itself runs fine in that session: install a JDK in the distro
(`sudo pacman -S jdk8-openjdk`) and use `./movietool.sh` as on desktop.

## Troubleshooting

- **"no Java runtime found"** — install Java 8+ or set `JAVA_HOME`.
- **"No display is available"** — you started the GUI on a headless machine;
  use the command line (`movietool help`).
- **Names reported as unrecognised** — run `movietool conventions` and try
  restricting detection with `-c`, or rename with a `--pattern`.
- **Subtitles not found** — put them in a `Subtitles` folder inside the videos
  folder (used automatically), or point at them with `-s <folder>`; per-episode
  download folders inside either location are scanned too.
- **Accents/international characters look wrong** — the tool always reads and
  writes UTF-8; the launchers set `-Dfile.encoding=UTF-8` for you.
