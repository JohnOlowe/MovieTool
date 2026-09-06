#!/usr/bin/env bash
# ============================================================================
# termux-audio-test.sh - run this in a terminal INSIDE the XFCE session.
# Verifies that sound from the proot session actually reaches the phone.
# ============================================================================
set -u
fail=0

echo "== environment =="
printf '  DISPLAY=%s\n' "${DISPLAY:-<unset>}"
printf '  PULSE_SERVER=%s\n' "${PULSE_SERVER:-<unset>}"
if [ -z "${PULSE_SERVER:-}" ]; then
  echo "  !! PULSE_SERVER is empty - apps will not find PulseAudio."
  echo "     Start the session with termux-session.sh (the audio-fixed launcher)."
  fail=1
fi

echo "== pulse server =="
if ! command -v pactl >/dev/null 2>&1; then
  echo "  !! pactl not installed:  sudo pacman -S pulseaudio"
  exit 1
fi
pactl info 2>&1 | sed 's/^/  /' | head -8

echo "== sinks (playback hardware) =="
pactl list short sinks 2>/dev/null | sed 's/^/  /'
if ! pactl list short sinks 2>/dev/null | grep -q .; then
  echo "  !! no sink found - on the Termux side run:"
  echo "       pactl load-module module-sles-sink"
  fail=1
fi

echo "== test sound =="
sample=""
for candidate in \
    /usr/share/sounds/freedesktop/stereo/complete.oga \
    /usr/share/sounds/freedesktop/stereo/bell.oga; do
  if [ -f "$candidate" ]; then sample="$candidate"; break; fi
done
if [ -n "$sample" ] && command -v paplay >/dev/null 2>&1; then
  if paplay "$sample"; then
    echo "  played: $sample"
    echo "  (you should have heard it on the phone just now)"
  else
    echo "  !! paplay failed - see the pactl output above"
    fail=1
  fi
else
  echo "  no test tone available, install one:"
  echo "    sudo pacman -S sound-theme-freedesktop"
fi

echo
if [ "$fail" = 0 ]; then
  echo "AUDIO OK"
else
  echo "AUDIO PROBLEMS FOUND (see the !! lines above)"
fi
exit "$fail"
