#!/data/data/com.termux/files/usr/bin/bash
# ============================================================================
# termux-session.sh - Termux X11 + PulseAudio + proot XFCE4 launcher
#
# What was wrong with the previous version:
#
#   1. NO AUDIO (the big one): the XFCE session was started with
#          su - DJBeloved -c "env DISPLAY=:0 startxfce4"
#      `su -` starts a LOGIN shell and wipes nearly the whole inherited
#      environment, so the PULSE_SERVER=127.0.0.1 exported a line earlier
#      never reached XFCE or anything it launched. Every app then looked
#      for a PulseAudio socket the session could not use -> silence.
#      DISPLAY had exactly the same problem (which is why the old script
#      re-set it with `env`) - PULSE_SERVER needed the same treatment.
#
#   2. The TCP module was loaded with `pacmd` right after `pulseaudio
#      --start`. That is a race: the daemon is not always ready to accept
#      pacmd yet, and the module silently ends up unloaded. It is now
#      passed to the daemon itself with --load.
#
#   3. `module-sles-source` is the MICROPHONE (capture only). Playback goes
#      through the OpenSL ES sink that Termux PulseAudio ships. The script
#      now makes sure a sink exists and reports it at startup.
#
#   4. `onboard` ran after `su -c "startxfce4"` returned - i.e. only after
#      the desktop had been closed - and without DISPLAY, so it could never
#      appear. It now starts inside the session, before the desktop.
#
#   5. `sleep 3` blind wait replaced by a poll for the real X socket.
#
# How audio is "bound" to the phone hardware (nothing extra needed):
#   proot app -> PulseAudio TCP 127.0.0.1:4713 -> Termux PulseAudio
#             -> OpenSL ES -> Android audio stack
#             -> speaker / 3.5mm / Bluetooth / USB, whichever is connected.
# Android picks the physical output; the session only has to reach the
# Termux sound server, which the PULSE_SERVER fix handles.
#
# If apps randomly die (PulseAudio included) on Android 12+, that is the
# phantom process killer, not this script. One-time fix via adb:
#   adb shell settings put global settings_enable_monitor_phantom_procs false
#   # or
#   adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent; \
#              /system/bin/device_config put activity_manager max_phantom_processes 2147483647"
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
# ----------------------------------------------------------------------------

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export XDG_RUNTIME_DIR="${TMPDIR:-$PREFIX/tmp}"
mkdir -p "$XDG_RUNTIME_DIR"

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

# ---- 2. termux-x11 ----------------------------------------------------------
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

# ---- 3. The proot session ---------------------------------------------------
# PULSE_SERVER / DISPLAY / XDG_RUNTIME_DIR are exported INSIDE the su'd shell
# because `su -` would otherwise throw them away (this is the audio fix).
# `onboard` starts in the background before startxfce4 takes over the shell.
BIND_ARGS=()
for bind in "${BINDS[@]}"; do
  BIND_ARGS+=(--bind "${bind%%:*}:${bind#*:}")
done

INNER="su - ${PROOT_USER} -c 'env DISPLAY=${DISPLAY_NO} PULSE_SERVER=127.0.0.1 XDG_RUNTIME_DIR=/tmp sh -c \"onboard & exec startxfce4\"'"

proot-distro login "${BIND_ARGS[@]}" "$PROOT_DISTRO" --shared-tmp -- /bin/bash -c "$INNER"

# The desktop was closed: release the wake lock again.
termux-wake-unlock 2>/dev/null || true
exit 0
