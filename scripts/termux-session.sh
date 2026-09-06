#!/data/data/com.termux/files/usr/bin/bash
# ============================================================================
# termux-session.sh - Termux X11 + PulseAudio + proot XFCE4 launcher
#
# Fixes over the original hand-rolled script, in the order they bite:
#
#   1. NO AUDIO: `su -` starts a login shell and wipes the environment, so
#      the PULSE_SERVER=127.0.0.1 exported before it never reached XFCE.
#      PULSE_SERVER / DISPLAY / XDG_RUNTIME_DIR are now set INSIDE the
#      session (DISPLAY had the same problem - the old script re-set it
#      with `env` while PULSE_SERVER was left behind).
#
#   2. The PulseAudio TCP module is passed to the daemon with --load
#      instead of racing `pacmd` right after --start.
#
#   3. module-sles-source is the MICROPHONE. The launcher also sets it (not
#      the OpenSL_ES_sink.monitor output monitor) as the DEFAULT SOURCE,
#      otherwise recording apps capture playback/silence.
#
#   4. D-Bus session bus: Arch compiles out D-Bus autolaunch ("Using X11
#      for dbus-daemon autolaunch was disabled at compile time") so nothing
#      created a session bus and XFCE's startup all failed. The session is
#      now wrapped in dbus-launch.
#
#   5. XDG_RUNTIME_DIR must NOT be /tmp (mode 041777 - dbus refuses it:
#      'can be written by others'). A private 0700 directory is created.
#
#   6. X socket is awaited (no blind sleep), wake lock held, onboard is
#      started inside the session with a display.
#
#   7. Bind sources that do not exist (e.g. an unmounted SD card) are
#      skipped with a warning instead of proot erroring on them.
#
# PortAudio apps (Audacity, ...) seeing NO devices / "Error recording 0":
#   run once:  AUTO_FIX_BRIDGE=1 ./scripts/termux-session.sh
# XFCE aborting with 'Gtk:ERROR ... image-missing.svg ... Bail out!':
#   run once:  AUTO_FIX_DESKTOP=1 ./scripts/termux-session.sh
#   (or just:  AUTO_FIX=1 ...  for both; and try `pkg upgrade proot` in
#   Termux - newer proot fixes the bwrap/namespace issue upstream.)
# Every start re-verifies both fixes (cached per boot when OK).
#
# If apps randomly die (PulseAudio included) on Android 12+, that is the
# phantom process killer, not this script. One-time fix via adb:
#   adb shell settings put global settings_enable_monitor_phantom_procs false
#
# Verify sound inside the XFCE session with scripts/termux-audio-test.sh
# ============================================================================

set -u

# ---------------------------------------------------------------- tweak me --
PROOT_DISTRO="${PROOT_DISTRO:-archlinux}"
PROOT_USER="${PROOT_USER:-DJBeloved}"
DISPLAY_NO="${DISPLAY_NO:-:0}"
# host path inside Termux : path inside the proot session
BINDS=(
  "/storage/67FE-19FE:/storage/sdcard"
  "/storage/EBC3-7839:/storage/sdcard1"
)
# AUTO_FIX=1 fixes everything at once; AUTO_FIX_BRIDGE=1 / AUTO_FIX_DESKTOP=1
# target the audio / desktop fixes individually.
# ----------------------------------------------------------------------------

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
export XDG_RUNTIME_DIR="${TMPDIR:-$PREFIX/tmp}"
mkdir -p "$XDG_RUNTIME_DIR"
RUNTIME_DIR_IN_DISTRO="/tmp/xdg-${PROOT_USER}"

fix_requested() {
    [ "${AUTO_FIX:-0}" = "1" ] || [ "${!1:-0}" = "1" ]
}

# Keep the device awake so PulseAudio is not frozen while backgrounded.
termux-wake-lock 2>/dev/null || true

# ---- 1. PulseAudio on the Android/Termux side ------------------------------
pulseaudio --kill 2>/dev/null || true
sleep 0.5
# The TCP module loads WITH the daemon: no pacmd race. Loopback only.
if ! pulseaudio --start \
     --exit-idle-time=-1 \
     --load="module-native-protocol-tcp listen=127.0.0.1 auth-ip-acl=127.0.0.1 auth-anonymous=1"; then
  echo "[audio] ERROR: PulseAudio failed to start" >&2
  exit 1
fi

# Microphone capture. Fails harmlessly when the permission is missing.
pactl load-module module-sles-source 2>/dev/null || true
# Playback sink for builds that do not auto-load one (no-op otherwise).
pactl load-module module-sles-sink 2>/dev/null || true

echo "[audio] $(pactl info 2>/dev/null | grep -m1 'Default Sink' \
  || echo 'WARNING: no default sink found - check Termux pulseaudio')"

# Make the real microphone the default source. Without this the default
# source is OpenSL_ES_sink.monitor - the OUTPUT monitor - and every recording
# app silently captures playback (or silence) instead of the mic.
MIC="$(pactl list short sources 2>/dev/null | awk '$2 !~ /\.monitor$/ { print $2; exit }')"
if [ -n "$MIC" ]; then
  pactl set-default-source "$MIC" 2>/dev/null || true
  echo "[audio] microphone ready: $MIC"
else
  echo "[audio] WARNING: no microphone source (only output monitors exist)."
  echo "[audio]   1. Android: Settings > Apps > Termux > Permissions > Microphone = allow"
  echo "[audio]   2. Termux:  pactl load-module module-sles-source   (see any error?)"
fi

# ---- 2. proot binds ---------------------------------------------------------
# Missing sources (unmounted SD card etc.) are skipped: proot would warn on
# every single one and the card can simply be remounted later.
BIND_ARGS=()
for bind in "${BINDS[@]}"; do
  src="${bind%%:*}"
  dst="${bind#*:}"
  if [ -e "$src" ]; then
    BIND_ARGS+=(--bind "$src:$dst")
  else
    echo "[bind] WARNING: $src does not exist right now - skipping (remount the storage and restart to include it)"
  fi
done
if [ -d "$SCRIPT_DIR" ]; then
  BIND_ARGS+=(--bind "$SCRIPT_DIR:/mnt/movietool-scripts")
fi
BRIDGE="/mnt/movietool-scripts/proot-audio-bridge.sh"
DESKTOP_FIX="/mnt/movietool-scripts/proot-desktop-fix.sh"

# ---- 3. one-time fixes, verified on every start ----------------------------
if fix_requested AUTO_FIX_BRIDGE; then
  echo "[alsa] installing the ALSA->PulseAudio bridge inside '$PROOT_DISTRO' ..."
  if proot-distro login "${BIND_ARGS[@]}" "$PROOT_DISTRO" --shared-tmp -- \
       /bin/bash -c "bash $BRIDGE fix && touch /tmp/.movietool-bridge-ok"; then
    echo "[alsa] bridge installed - PortAudio apps will list devices"
  else
    echo "[alsa] bridge install failed - PortAudio apps will still see no devices"
  fi
elif [ -f "$PREFIX/tmp/.movietool-bridge-ok" ]; then
  echo "[alsa] bridge verified earlier this boot (remove $PREFIX/tmp/.movietool-bridge-ok to re-check)"
elif proot-distro login "${BIND_ARGS[@]}" "$PROOT_DISTRO" --shared-tmp -- \
       /bin/bash -c "bash $BRIDGE check" >/dev/null 2>&1; then
  touch "$PREFIX/tmp/.movietool-bridge-ok" 2>/dev/null || true
  echo "[alsa] bridge verified (PortAudio apps will list devices)"
else
  echo "[alsa] NOTE: PortAudio apps (Audacity etc.) will show NO devices yet."
  echo "[alsa]       Fix once with:  AUTO_FIX_BRIDGE=1 $SCRIPT_DIR/termux-session.sh"
fi

if fix_requested AUTO_FIX_DESKTOP; then
  echo "[gtk] installing the desktop fix (SVG loader + D-Bus) inside '$PROOT_DISTRO' ..."
  if proot-distro login "${BIND_ARGS[@]}" "$PROOT_DISTRO" --shared-tmp -- \
       /bin/bash -c "bash $DESKTOP_FIX fix && touch /tmp/.movietool-desktop-ok"; then
    echo "[gtk] desktop fix installed - XFCE will start"
  else
    echo "[gtk] desktop fix incomplete - XFCE may still abort on icons"
  fi
elif [ -f "$PREFIX/tmp/.movietool-desktop-ok" ]; then
  echo "[gtk] desktop fix verified earlier this boot (remove $PREFIX/tmp/.movietool-desktop-ok to re-check)"
elif proot-distro login "${BIND_ARGS[@]}" "$PROOT_DISTRO" --shared-tmp -- \
       /bin/bash -c "bash $DESKTOP_FIX check" >/dev/null 2>&1; then
  touch "$PREFIX/tmp/.movietool-desktop-ok" 2>/dev/null || true
  echo "[gtk] desktop verified (SVG loading + D-Bus OK)"
else
  echo "[gtk] NOTE: XFCE will likely abort ('Gtk:ERROR ... image-missing.svg')."
  echo "[gtk]       Fix once with:  AUTO_FIX_DESKTOP=1 $SCRIPT_DIR/termux-session.sh"
  echo "[gtk]       Or upgrade proot:  pkg upgrade proot"
fi

# ---- 4. termux-x11 ----------------------------------------------------------
am force-stop com.termux.x11 2>/dev/null || true
# Pattern includes ":0" so it can never match this script's own name.
pkill -9 -f "termux-x11 :0" 2>/dev/null || true
termux-x11 "$DISPLAY_NO" >/dev/null 2>&1 &

# Wait for the X socket instead of a blind sleep.
XSOCKET=""
for _ in $(seq 1 40); do
  for candidate in "$XDG_RUNTIME_DIR/.X11-unix/X0" \
                   "$PREFIX/tmp/.X11-unix/X0" \
                   "/tmp/.X11-unix/X0"; do
    if [ -S "$candidate" ]; then
      XSOCKET="$candidate"
      break 2
    fi
  done
  sleep 0.5
done
if [ -n "$XSOCKET" ]; then
  echo "[x11] socket ready: $XSOCKET"
else
  echo "[x11] WARNING: no X socket after 20s, starting the activity anyway"
fi

am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1 || true
sleep 1

# ---- 5. The proot session ---------------------------------------------------
# The /tmp of the distro is --shared-tmp (world-writable), which dbus rejects
# as XDG_RUNTIME_DIR ('can be written by others (mode 041777)'). A private
# 0700 directory is created instead, and the whole session is wrapped in
# dbus-launch because Arch compiles out D-Bus autolaunch.
rm -rf "${TMPDIR:-$PREFIX/tmp}/xdg-${PROOT_USER}" 2>/dev/null || true

INNER="su - ${PROOT_USER} -c \"XDG_RUNTIME_DIR=${RUNTIME_DIR_IN_DISTRO} PULSE_SERVER=127.0.0.1 DISPLAY=${DISPLAY_NO} sh -c 'mkdir -p ${RUNTIME_DIR_IN_DISTRO} && chmod 700 ${RUNTIME_DIR_IN_DISTRO} && export XDG_RUNTIME_DIR PULSE_SERVER DISPLAY && exec dbus-launch --exit-with-session sh -c \\\"onboard >/dev/null 2>&1 & exec startxfce4\\\"'\""

proot-distro login "${BIND_ARGS[@]}" "$PROOT_DISTRO" --shared-tmp -- /bin/bash -c "$INNER"

# The desktop was closed: release the wake lock again.
termux-wake-unlock 2>/dev/null || true
exit 0
