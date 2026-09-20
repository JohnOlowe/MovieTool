#!/usr/bin/env bash
# ============================================================================
# proot-desktop-fix.sh - stop GTK from aborting XFCE4 inside proot
#
# THE CRASH
#   Bail out! Gtk:ERROR: gtkiconhelper.c:495: ensure_surface_for_gicon:
#   Failed to load .../image-missing.svg: Loader process exited early ...
#   Command: "bwrap" "--unshare-all" ...
#
#   gdk-pixbuf 2.44+ / GTK load ALL images through the sandboxed glycin
#   loaders, which shell out to bubblewrap --unshare-all. proot has no
#   namespaces and (old proot) no mount emulation, bwrap dies, and GTK
#   treats a failed icon load as fatal. Disabling glycin is NOT an option
#   on 2.44: the classic loader system was removed, so with glycin hidden
#   GTK fails with "No image loaders are configured" - even with librsvg
#   installed. Same bug: termux/proot-distro#644 (bwrap support landed in
#   proot itself, termux/proot#359 - so `pkg upgrade proot` in Termux is
#   the proper cure and makes the native sandbox work again).
#
# THE FIX APPLIED HERE (as root inside the distro):
#   1. Restore the glycin loader configs if a previous MovieTool run hid
#      them (renamed *.movietool-disabled).
#   2. Install a bwrap SHIM at /usr/local/bin/bwrap that parses bwrap's
#      command line and execs the loader directly. The loader then runs
#      unsandboxed - inside proot the sandbox adds nothing anyway; the
#      whole distro is a chroot. /usr/local/bin precedes /usr/bin in PATH,
#      the real bwrap stays untouched at /usr/bin/bwrap. The shim is
#      PERMANENT: a "native bwrap works" probe cannot be trusted (a minimal
#      probe passes while glycin's real --seccomp invocation still dies, and
#      auto-removing a working shim crashed the desktop on the next boot).
#   3. Widen the pacman mirror set (local file edit, no network) and install
#      what is missing (dbus, xtrlock, xfce4-genmon-plugin) - but ONLY the
#      missing ones, so a flaky mirror or mobile-data outage can never block
#      the desktop fix when everything is already in place.
#   4. DESKTOP INTEGRATION (makes proot XFCE behave like a real desktop):
#      - battery widget for the panel (xfce4-genmon-plugin reading the
#        launcher's feed) + a battery popup (Ctrl+Alt+B);
#      - lock-screen via xtrlock (Ctrl+Alt+L) and a xflock4 shim;
#      - logout / poweroff / reboot that act on the SESSION, never the
#        phone ('reboot' restarts XFCE via a flag the launcher watches);
#      - the (here useless, log-spamming) xfce4-power-manager autostart is
#        hidden - the genmon widget replaces its battery display;
#      - a session-start setup wires panel entry, shortcuts, file-manager
#        bookmarks for /storage/android and desktop icons, idempotently.
#
# RUN AS ROOT INSIDE THE DISTRO - usually via the session launcher:
#     AUTO_FIX_DESKTOP=1 ./scripts/termux-session.sh     (run once)
# Modes: fix (default) | check.  ROOT_DIR=<dir>/BWRAP_WORKS=0 for tests.
# ============================================================================
set -u

MODE="${1:-fix}"
R="${ROOT_DIR:-}"
# shellcheck source=proot-lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)/proot-lib.sh"

SHIM_PATH="$R/usr/local/bin/bwrap"

# ------------------------------------------------------------- probing ------

# Does the bwrap that PATH resolves to actually work here? In ROOT_DIR test
# mode the caller decides via BWRAP_WORKS (default: broken, the proot
# situation); a MovieTool shim counts as working, which is its whole job.
bwrap_works() {
    if in_test_mode; then
        if [ "${BWRAP_WORKS:-0}" = "1" ]; then return 0; fi
        [ -e "$R/usr/local/bin/bwrap" ]
        return
    fi
    # Field lesson: non-login shells (proot-distro login ... -c) can lack
    # /usr/local/bin in PATH, which made the probe falsely report "shim did
    # not take effect" while the desktop - launched with the normal PATH -
    # used the shim just fine. Probe with the standard Arch PATH.
    PATH="/usr/local/sbin:/usr/local/bin:/usr/bin:/bin"
    export PATH
    command -v bwrap >/dev/null 2>&1 || return 1
    if [ "$(command -v bwrap)" = "/usr/local/bin/bwrap" ]; then
        return 0   # our shim: works by construction
    fi
    bwrap --unshare-all --ro-bind / / /bin/true >/dev/null 2>&1
}



# Path of the glycin loader configuration (glob: the compat version segment
# of the directory name varies between releases).
glycin_conf_dir() {
    local d
    for d in "$R"/usr/share/glycin-loaders*; do
        case "$d" in *.movietool-disabled*) continue ;; esac
        [ -d "$d" ] && { printf '%s' "$d"; return 0; }
    done
    return 1
}

glycin_disabled_dir() {
    local d
    for d in "$R"/usr/share/glycin-loaders*.movietool-disabled; do
        [ -d "$d" ] && { printf '%s' "$d"; return 0; }
    done
    return 1
}

dbus_launch_present() {
    if [ -n "$R" ]; then [ -e "$R/usr/bin/dbus-launch" ]; return; fi
    command -v dbus-launch >/dev/null 2>&1
}

# --- optional desktop-integration pieces (installed when missing) ---------
in_distro() {  # file exists inside the (possibly fake) distro
    if [ -n "$R" ]; then [ -e "$R$1" ]; else [ -e "$1" ]; fi
}
xtrlock_present()  { in_distro /usr/bin/xtrlock; }
genmon_present()   { in_distro /usr/lib/xfce4/panel/plugins/libgenmon.so; }
power_manager_autostart_file() { printf '%s' "$R/etc/xdg/autostart/xfce4-power-manager.desktop"; }

# The whole SVG pipeline is fine when glycin configs exist and the effective
# bwrap works (native or shimmed).
svg_path_ok() {
    glycin_conf_dir >/dev/null && bwrap_works
}

case "$MODE" in
    check)
        if svg_path_ok && dbus_launch_present; then
            log "desktop OK: SVG loading and the D-Bus session bus will work"
            exit 0
        fi
        glycin_conf_dir >/dev/null || fail "glycin loader configs missing - GTK has no image loaders at all"
        svg_path_ok || fail "bwrap does not work under proot and no shim is installed (the 'Bail out!' crash)"
        dbus_launch_present || fail "dbus-launch missing - XFCE will have no D-Bus session bus"
        exit 1
        ;;
    fix)
        ;;
    *)
        printf 'usage: %s [fix|check]\n' "$0" >&2
        exit 2
        ;;
esac

# ------------------------------------------------- 1. glycin configs --------
# Always present - hiding them leaves GTK with zero loaders on 2.44+.
if glycin_conf_dir >/dev/null; then
    log "glycin loader configs: present"
elif DIR="$(glycin_disabled_dir)"; then
    NEWDIR="${DIR%.movietool-disabled}"
    if [ -e "$NEWDIR" ]; then
        fail "cannot restore $DIR: $NEWDIR already exists"
    else
        mv "$DIR" "$NEWDIR"
        log "restored glycin loader configs: $NEWDIR (hiding them left GTK without any image loader)"
    fi
else
    log "no glycin loader configs found - if SVG still fails, install the 'glycin-loaders' package"
fi

# ------------------------------------------------- 2. bwrap shim ------------
# Installed unconditionally and KEPT. An earlier revision probed native bwrap
# and removed the shim when the probe passed - the probe then saw OUR SHIM,
# returned OK, the script concluded "glycin runs natively", deleted the
# working shim, and the desktop crashed again on the next boot. Under proot
# the sandbox cannot work (no namespaces, no mount(), no seccomp), so the
# shim is what makes image loading possible here: always write it.
log "installing the bwrap shim (permanent under proot - native bwrap cannot sandbox here)"
mkdir -p "${SHIM_PATH%/*}"
cat > "$SHIM_PATH" <<'SHIM'
#!/usr/bin/env bash
# MovieTool bwrap shim for proot (generated by proot-desktop-fix.sh).
#
# glycin/gdk-pixbuf 2.44+ sandbox image loaders with bubblewrap, which needs
# Linux namespaces and mount() - unavailable inside proot, so every image
# load dies and GTK aborts ("Bail out! ... image-missing.svg"). This shim
# understands bwrap's command line, ignores the sandbox setup and execs the
# wrapped command directly. Under proot the sandbox is meaningless anyway:
# the entire distro already runs inside a chroot.
#
# bwrap grammar used here (all current glycin calls):
#   [options...] COMMAND [args...]
#   options: --flag | --opt ARG | --opt ARG1 ARG2   (values never start with -)
set -u

shim_warn() { printf 'bwrap-shim: %s\n' "$*" >&2; }

args=("$@")
i=0
n=${#args[@]}
while [ "$i" -lt "$n" ]; do
    a="${args[$i]}"
    case "$a" in
        # ---- options taking NO argument
        --unshare-all|--unshare-user|--unshare-user-try|--unshare-ipc|--unshare-pid|--unshare-net|--unshare-netns|--unshare-uts|--unshare-cgroup|--unshare-cgroup-try|--die-with-parent|--clearenv|--new-session|--as-pid-1)
            i=$((i + 1))
            ;;
        # ---- options taking ONE argument
        --chdir|--proc|--dev|--tmpfs|--seccomp|--size|--perms|--sync-fd|--args|--cap-add|--cap-drop|--lock-file|--snippet-fd)
            i=$((i + 2))
            ;;
        # ---- options taking TWO arguments
        --bind|--ro-bind|--dev-bind|--bind-try|--ro-bind-try|--dev-bind-try|--setenv|--unsetenv|--symlink|--file|--bind-data|--ro-bind-data)
            i=$((i + 3))
            ;;
        # ---- end of options: everything after -- is the command
        --)
            i=$((i + 1))
            exec "${args[@]:$i}"
            ;;
        -*)
            shim_warn "unknown option '$a' - please report this; bwrap's option set changed"
            exit 64
            ;;
        *)
            # First non-option token: this is COMMAND. exec keeps open fds,
            # which matters because glycin passes --dbus-fd/--seccomp fds.
            exec "${args[@]:$i}"
            ;;
    esac
done
shim_warn "no command found in arguments - nothing to do"
exit 64
SHIM
chmod 755 "$SHIM_PATH"
if bwrap_works; then
    log "bwrap shim: in place at $SHIM_PATH - image loaders will run (unsandboxed, which is correct under proot)"
else
    fail "shim did not take effect - check that PATH has /usr/local/bin before /usr/bin"
fi

# ------------------------------------------------- 3. mirrors + packages ----
# Widen the pacman mirror set on every run: a pure local file edit - no
# network, idempotent - so future package installs survive networks that
# reject TLS to the geo mirror (pacman then has mirrors on other hosts).
repair_arch_mirrors
# Only what is actually missing goes through pacman - when everything is
# already installed pacman is skipped ENTIRELY and a broken mirror or dead
# connection can no longer mark the desktop fix incomplete (field lesson).
MISSING_ARCH=""
MISSING_DEB=""
if dbus_launch_present; then
    log "dbus: present"
else
    log "dbus: missing - will install (session bus)"
    MISSING_ARCH="$MISSING_ARCH dbus"
    MISSING_DEB="$MISSING_DEB dbus dbus-x11"
fi
if xtrlock_present; then
    log "xtrlock (lock screen): present"
else
    log "xtrlock: missing - will install (locks the session: Ctrl+Alt+L)"
    MISSING_ARCH="$MISSING_ARCH xtrlock"
    MISSING_DEB="$MISSING_DEB xtrlock"
fi
if genmon_present; then
    log "xfce4-genmon-plugin (panel widgets): present"
else
    log "xfce4-genmon-plugin: missing - will install (battery widget on the panel)"
    MISSING_ARCH="$MISSING_ARCH xfce4-genmon-plugin"
    MISSING_DEB="$MISSING_DEB xfce4-genmon-plugin"
fi
if [ -z "${MISSING_ARCH// /}" ]; then
    log "all desktop packages present - skipping pacman (nothing to install)"
else
    log "installing:${MISSING_ARCH}"
    install_packages "arch:$MISSING_ARCH" "deb:$MISSING_DEB" || \
        fail "package install failed; install manually:${MISSING_ARCH}"
fi

# ------------------------------------------------- 4. desktop integration --
# Everything here is additive sugar: the desktop works without it, but with
# it proot XFCE behaves like a real desktop (battery, lock, power shims).
LOCAL_BIN="$R/usr/local/bin"
mkdir -p "$LOCAL_BIN"

# --- 4a. battery widget for the panel (xfce4-genmon-plugin) ----------------
cat > "$LOCAL_BIN/movietool-battery-widget" <<'WIDGET'
#!/bin/sh
# MovieTool battery panel widget (xfce4-genmon-plugin). Reads the feed the
# Termux-side launcher refreshes every 30 s: /tmp/.movietool-battery =
# "PERCENT STATE UNIX_TIME" (Termux $PREFIX/tmp IS this distro's /tmp).
FILE=/tmp/.movietool-battery
[ -r "$FILE" ] || { printf 'battery: no feed'; exit 0; }
read -r pct state ts < "$FILE" 2>/dev/null || { printf 'battery: no feed'; exit 0; }
case "$state" in
  Full|FULL|Charging|CHARGING) label="$state" ;;
  *)                           label="discharging" ;;
esac
now=$(date +%s)
if [ -n "$ts" ] && [ $((now - ts)) -gt 300 ]; then label="$label (stale)"; fi
printf '%s%%\n<txt>%s%% (%s)</txt>\n<tool>Phone battery: %s%% - %s\nClick for a popup</tool>\n<click>movietool-battery-popup</click>\n' \
    "$pct" "$pct" "$label" "$pct" "$label"
WIDGET

cat > "$LOCAL_BIN/movietool-battery-popup" <<'POPUP'
#!/bin/sh
# Pops the phone battery up as a desktop notification (also on Ctrl+Alt+B).
FILE=/tmp/.movietool-battery
if [ ! -r "$FILE" ]; then
  text="No battery feed (the session launcher writes it every 30 s)."
else
  read -r pct state ts < "$FILE" 2>/dev/null || pct=""
  if [ -z "$pct" ]; then
    text="No battery data yet."
  else
    case "$state" in
      Full|FULL)         text="$pct% - fully charged" ;;
      Charging|CHARGING) text="$pct% - charging" ;;
      *)                 text="$pct% - on battery" ;;
    esac
    now=$(date +%s)
    [ -n "$ts" ] && [ $((now - ts)) -gt 300 ] && text="$text (stale feed)"
  fi
fi
if command -v notify-send >/dev/null 2>&1; then
  notify-send "Phone battery" "$text" -i battery 2>/dev/null || printf '%s\n' "$text"
else
  printf 'Phone battery: %s\n' "$text"
fi
POPUP

# --- 4b. lock screen (xtrlock) + xflock4 shim ------------------------------
cat > "$LOCAL_BIN/lock-screen" <<'LOCK'
#!/bin/sh
# Locks the SESSION (not the phone): xtrlock grabs pointer+keyboard until
# the distro user's password is typed. Ctrl+Alt+L is wired to this.
if command -v xtrlock >/dev/null 2>&1; then
  exec xtrlock
fi
echo "lock-screen: xtrlock is not installed" >&2
exit 1
LOCK

cat > "$LOCAL_BIN/xflock4" <<'XFLOCK'
#!/bin/sh
# Xfce's xflock4, reimplemented for proot: the usual lockers (xscreensaver,
# xfce4-screensaver, ...) do not work here, xtrlock does.
exec lock-screen
XFLOCK

# --- 4c. session power shims: logout / reboot / poweroff -------------------
# These shadow /usr/bin's (which cannot work under proot anyway) and act on
# the SESSION only - the phone is never touched.
cat > "$LOCAL_BIN/logout-session" <<'LOGOUT'
#!/bin/sh
# Ends the XFCE session; the launcher on the Termux side then cleans up.
exec xfce4-session-logout --fast --logout
LOGOUT

cat > "$LOCAL_BIN/reboot" <<'REBOOT'
#!/bin/sh
# Restarts the DESKTOP: touches the flag the session launcher watches, then
# logs out. The launcher starts XFCE again ("reboot" = fresh desktop).
touch /tmp/.movietool-reboot 2>/dev/null || true
exec xfce4-session-logout --fast --logout
REBOOT

cat > "$LOCAL_BIN/poweroff" <<'POWEROFF'
#!/bin/sh
# "Power off" for the session: ends XFCE cleanly. The PHONE is not touched
# (that is Android's job). The session launcher stops afterwards.
exec xfce4-session-logout --fast --logout
POWEROFF

chmod 755 "$LOCAL_BIN/movietool-battery-widget" "$LOCAL_BIN/movietool-battery-popup" \
          "$LOCAL_BIN/lock-screen" "$LOCAL_BIN/xflock4" \
          "$LOCAL_BIN/logout-session" "$LOCAL_BIN/reboot" "$LOCAL_BIN/poweroff"

# --- 4d. menu + desktop entries --------------------------------------------
APPS="$R/usr/share/applications"
mkdir -p "$APPS"
write_desktop() {  # <id> <name> <exec> <icon>
    cat > "$APPS/$1.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$2
Comment=MovieTool session action (the phone is not affected)
Exec=$3
Icon=$4
Terminal=false
Categories=System;
NoDisplay=false
EOF
}
write_desktop movietool-lock     "Lock Screen (session)" "lock-screen"      "system-lock-screen"
write_desktop movietool-logout   "Log Out (session)"     "logout-session"   "system-log-out"
write_desktop movietool-reboot   "Reboot Session"        "reboot"           "view-refresh"
write_desktop movietool-poweroff "Power Off Session"     "poweroff"         "system-shutdown"

# --- 4e. hide xfce4-power-manager's autostart ------------------------------
# Without a system bus/upower it only spams CRITICALs and fights other
# instances ("Another power manager is already running"). The genmon widget
# replaces its battery display. One-time backup, idempotent.
PM_AUTO="$(power_manager_autostart_file)"
if [ -f "$PM_AUTO" ] && ! grep -q "^Hidden=true" "$PM_AUTO"; then
    [ -e "$PM_AUTO.movietool.bak" ] || cp "$PM_AUTO" "$PM_AUTO.movietool.bak" 2>/dev/null || true
    printf '\n# hidden by MovieTool: no system bus under proot; the genmon battery widget replaces it\nHidden=true\n' >> "$PM_AUTO"
    log "xfce4-power-manager autostart: hidden (it cannot work under proot; battery comes from the panel widget)"
fi

# --- 4f. session-start setup (runs as the session user via autostart) ------
cat > "$LOCAL_BIN/movietool-desktop-setup" <<'SETUP'
#!/bin/bash
# Runs at every session start (autostart entry below) and wires the extras
# into the user's live XFCE settings - idempotent, failures never fatal.
LOG="$HOME/.movietool-setup.log"
{
echo "---- MovieTool desktop setup $(date) ----"

# 1. battery widget on the first panel (genmon plugin)
n=0
while [ "$n" -lt 20 ]; do
  xfconf-query -c xfce4-panel -p /panels >/dev/null 2>&1 && break
  n=$((n + 1)); sleep 1
done
if xfconf-query -c xfce4-panel -p /panels >/dev/null 2>&1; then
  PANEL="$(xfconf-query -c xfce4-panel -p /panels 2>/dev/null | head -n1)"
  [ -n "$PANEL" ] || PANEL="panel-1"
  PROPS="/panels/$PANEL/plugin-ids"
  ids="$(xfconf-query -c xfce4-panel -p "$PROPS" 2>/dev/null || true)"
  have=0; max=0
  for id in $ids; do
    case "$id" in (*[!0-9]*|"") continue ;; esac
    [ "$id" -gt "$max" ] && max=$id
    t="$(xfconf-query -c xfce4-panel -p "/plugins/plugin-$id/type" 2>/dev/null || true)"
    [ "$t" = "genmon" ] && have=1
  done
  if [ "$have" = 0 ] && [ -e /usr/lib/xfce4/panel/plugins/libgenmon.so ]; then
    nid=$((max + 1))
    if xfconf-query -c xfce4-panel -p "/plugins/plugin-$nid/type" -n -t string -s genmon >/dev/null 2>&1; then
      cmd=(xfconf-query -c xfce4-panel -p "$PROPS")
      for id in $ids; do cmd+=(-t uint -s "$id"); done
      cmd+=(-t uint -s "$nid")
      if "${cmd[@]}" >/dev/null 2>&1; then
        echo "battery widget: added to $PANEL as plugin-$nid"
        xfce4-panel -r >/dev/null 2>&1 || true
      else
        echo "battery widget: could not update $PROPS"
      fi
    else
      echo "battery widget: could not register plugin-$nid"
    fi
  else
    echo "battery widget: already present (or plugin missing)"
  fi
else
  echo "battery widget: xfce4-panel config not reachable"
fi

# 2. keyboard shortcuts: Ctrl+Alt+L lock, Ctrl+Alt+B battery popup
xfconf-query -c xfce4-keyboard-shortcuts \
  -p "/xfwm4/custom/<Primary><Alt>l" -n -t string -s "lock-screen" >/dev/null 2>&1 \
  && echo "shortcut: Ctrl+Alt+L -> lock-screen" || echo "shortcut: Ctrl+Alt+L failed"
xfconf-query -c xfce4-keyboard-shortcuts \
  -p "/commands/custom/<Primary><Alt>b" -n -t string -s "movietool-battery-popup" >/dev/null 2>&1 \
  && echo "shortcut: Ctrl+Alt+B -> battery popup" || echo "shortcut: Ctrl+Alt+B failed"

# 3. file-manager bookmarks for the (hot-plug) phone storage
BK="$HOME/.gtk-bookmarks"
touch "$BK" 2>/dev/null || BK=""
if [ -n "$BK" ]; then
  add_bm() { grep -qxF "$1" "$BK" 2>/dev/null || printf '%s\n' "$1" >> "$BK"; }
  add_bm "file:///storage/android Phone storage (all volumes)"
  for v in /storage/android/*; do
    [ -d "$v" ] || continue
    case "$(basename "$v")" in emulated|self) continue ;; esac
    add_bm "file://$v SD/USB: $(basename "$v")"
  done
  echo "bookmarks: updated"
fi

# 4. desktop icons for the session power actions
mkdir -p "$HOME/Desktop" 2>/dev/null
for f in movietool-lock movietool-logout movietool-reboot movietool-poweroff; do
  [ -f "/usr/share/applications/$f.desktop" ] && \
    cp -f "/usr/share/applications/$f.desktop" "$HOME/Desktop/" 2>/dev/null || true
done
echo "desktop icons: updated"
echo "---- done ----"
} >> "$LOG" 2>&1
SETUP
chmod 755 "$LOCAL_BIN/movietool-desktop-setup"

cat > "$R/etc/xdg/autostart/movietool-desktop-setup.desktop" <<'AUTO'
[Desktop Entry]
Type=Application
Name=MovieTool desktop setup
Comment=Wires in the battery widget, shortcuts, bookmarks and session icons
Exec=/usr/local/bin/movietool-desktop-setup
Terminal=false
NoDisplay=true
X-GNOME-Autostart-enabled=true
AUTO

log "desktop integration: battery widget + lock (Ctrl+Alt+L) + session logout/reboot/poweroff installed"

# ------------------------------------------------- 5. verify ----------------
log "verification:"
if glycin_conf_dir >/dev/null; then
    log "  glycin loader configs: present"
else
    fail "  glycin loader configs: MISSING"
fi
if bwrap_works; then
    log "  bwrap (effective): OK"
else
    fail "  bwrap (effective): still broken"
fi
if dbus_launch_present; then
    log "  dbus-launch: present"
else
    fail "  dbus-launch: MISSING"
fi
if [ -x "$R/usr/local/bin/lock-screen" ] && [ -x "$R/usr/local/bin/poweroff" ]; then
    log "  session power shims (lock/logout/reboot/poweroff): present"
else
    fail "  session power shims: MISSING"
fi
if [ -e "$R/etc/xdg/autostart/movietool-desktop-setup.desktop" ]; then
    log "  session-start integration (panel widget, shortcuts, bookmarks): armed"
else
    fail "  session-start integration: MISSING"
fi
if svg_path_ok && dbus_launch_present; then
    log "desktop fix: OK - start the session normally"
    exit 0
fi
fail "desktop fix incomplete - see the !! lines above"
fail "also try updating proot itself (Termux):  pkg upgrade proot"
exit 1
