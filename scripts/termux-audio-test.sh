#!/usr/bin/env bash
# ============================================================================
# termux-audio-test.sh - run INSIDE the XFCE session (any shell in the proot
# distro). Checks the whole audio path layer by layer:
#
#   1. PulseAudio server reachable (env / client.conf routing)
#   2. playback sink          (speaker / headphones)
#   3. ALSA bridge            (what PortAudio V19.7.0-devel scans)
#   4. microphone round trip  (records 3 s and plays it back)
#
# Exit code 0 only when everything works.
# ============================================================================
set -u
fail=0

echo "== 1. PulseAudio server =="
if ! command -v pactl >/dev/null 2>&1; then
  echo "  !! pactl missing - install the client tools:"
  echo "       Arch:   sudo pacman -S pulseaudio"
  echo "       Debian: sudo apt install pulseaudio-utils"
  exit 1
fi
if pactl info >/tmp/movietool-pactl-info 2>/dev/null; then
  grep -E "Server Name|Default Sink|Default Source" /tmp/movietool-pactl-info | sed 's/^/  /'
  rm -f /tmp/movietool-pactl-info
else
  echo "  !! cannot reach PulseAudio on 127.0.0.1."
  echo "     Start the session with scripts/termux-session.sh (it runs PulseAudio"
  echo "     in Termux with the TCP module and passes PULSE_SERVER into the desktop;"
  echo "     scripts/proot-audio-bridge.sh also pins it in /etc/pulse/client.conf)."
  exit 1
fi
if [ -z "${PULSE_SERVER:-}" ]; then
  echo "  note: PULSE_SERVER is not set in this shell;"
  echo "        /etc/pulse/client.conf default-server is doing the routing (fine)."
fi

echo "== 2. playback sink (speaker/headphones) =="
pactl list short sinks 2>/dev/null | sed 's/^/  /'
if ! pactl list short sinks 2>/dev/null | grep -q .; then
  echo "  !! no sink. On the Termux side (outside the distro) run:"
  echo "       pactl load-module module-sles-sink"
  fail=1
fi

echo "== 3. ALSA bridge (what PortAudio scans) =="
if command -v aplay >/dev/null 2>&1; then
  pcms=$(aplay -L 2>/dev/null | grep -E "^(default|pulse)" | tr '\n' ' ')
  if [ -n "$pcms" ]; then
    echo "  OK: PortAudio will list devices ($pcms)"
  else
    echo "  !! no 'default'/'pulse' PCM - PortAudio apps will show NO devices"
    echo "     (\"Error recording 0 Success\"). Fix once, as the Termux user:"
    echo "       AUTO_FIX_BRIDGE=1 ./scripts/termux-session.sh"
    fail=1
  fi
else
  echo "  aplay missing - install it to run this check:"
  echo "       Arch: sudo pacman -S alsa-utils ; Debian: sudo apt install alsa-utils"
fi

echo "== 4. microphone round trip (3 seconds) =="
if command -v parecord >/dev/null 2>&1 && command -v paplay >/dev/null 2>&1; then
  pactl list short sources 2>/dev/null | while IFS='	' read -r _ name _; do
    echo "  source: $name"
  done
  echo "  recording 3 s - say something ..."
  rm -f /tmp/movietool-mic-test.wav
  timeout 4 parecord --file-format=wav /tmp/movietool-mic-test.wav 2>/dev/null || true
  size=$(wc -c < /tmp/movietool-mic-test.wav 2>/dev/null || echo 0)
  if [ "${size:-0}" -gt 4000 ]; then
    echo "  captured $size bytes; playing it back ..."
    if paplay /tmp/movietool-mic-test.wav 2>/dev/null; then
      echo "  if you heard yourself: microphone OK"
    else
      echo "  !! playback of the recording failed"
      fail=1
    fi
  else
    echo "  !! nothing captured. Checklist:"
    echo "     1. Termux side:  pactl load-module module-sles-source"
    echo "     2. Android: Settings > Apps > Termux > Permissions > Microphone = allow"
    echo "     3. Android 12+: exempt Termux from the phantom process killer (adb)"
    fail=1
  fi
  rm -f /tmp/movietool-mic-test.wav
else
  echo "  parecord/paplay missing - install:"
  echo "       Arch: sudo pacman -S pulseaudio ; Debian: sudo apt install pulseaudio-utils"
fi

echo
if [ "$fail" = 0 ]; then
  echo "AUDIO PATH OK"
else
  echo "AUDIO PROBLEMS FOUND (see the !! lines above)"
fi
exit "$fail"
