#!/usr/bin/env bash
# ============================================================================
# termux-audio-devices.sh - control WHICH input/output device the phone's
# PulseAudio uses: built-in mic vs USB mic, speaker vs headphones, etc.
#
# Works BOTH on the Termux side and inside the XFCE session - it is the same
# PulseAudio server either way (127.0.0.1:4713).
#
# Commands:
#   list                show sinks (outputs) and sources (inputs) + defaults
#   mic [<n>|<name>]    show sources, or set the default recording source
#   out [<n>|<name>]    show sinks,   or set the default playback sink
#   alsa-sync           give every source/sink its OWN ALSA device name in
#                       /etc/asound.conf (inside the distro) so Audacity can
#                       pick them directly instead of one "default"
#
# HOW DEVICES APPEAR HERE (read this first)
#   Termux's PulseAudio captures through Android's OpenSL ES API, which
#   always follows Android's own routing. Usually you see ONE source,
#   OpenSL_ES_source, and whichever hardware Android routes is what you get:
#   plug a USB mic in (OTG) and most Android versions switch the input to it
#   automatically - unplug to go back to the built-in mic. So:
#     1. Plug the USB mic in, run:  termux-audio-devices.sh list
#     2. If a second source appears -> switch with:  mic 2
#     3. If only one appears, Android already routed it - confirm with
#        scripts/termux-audio-test.sh (its round trip records whatever is
#        live), or run  alsa-sync  and pick per-device entries in Audacity.
#   Inside the desktop, pavucontrol gives you the same switching plus
#   per-application device choice while recording:
#       sudo pacman -S pavucontrol && pavucontrol   (Recording tab)
# ============================================================================
set -u

usage() {
    sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

[ $# -lt 1 ] && usage 0
COMMAND="$1"
shift || true

if ! command -v pactl >/dev/null 2>&1; then
    echo "pactl not found - install the PulseAudio client tools first:"
    echo "  Arch:   sudo pacman -S pulseaudio"
    echo "  Termux: pkg install pulseaudio"
    exit 1
fi

# ---------------------------------------------------------------- enumerate --
# One line per device:  index TAB name TAB description
enumerate_sources() {
    pactl list sources 2>/dev/null | awk '
        /^Source #[0-9]+/ { idx=$2; gsub("[:#]","",idx); name=""; desc="" }
        /^[\t ]*Name:/ { name=$2 }
        /^[\t ]*Description:/ { $1=""; sub(/^[\t ]+/,""); desc=$0 }
        /^[\t ]*Properties:/ { if (name != "") { print idx "\t" name "\t" desc; name="" } }
        END { if (name != "") print idx "\t" name "\t" desc }
    '
}

enumerate_sinks() {
    pactl list sinks 2>/dev/null | awk '
        /^Sink #[0-9]+/ { idx=$2; gsub("[:#]","",idx); name=""; desc="" }
        /^[\t ]*Name:/ { name=$2 }
        /^[\t ]*Description:/ { $1=""; sub(/^[\t ]+/,""); desc=$0 }
        /^[\t ]*Properties:/ { if (name != "") { print idx "\t" name "\t" desc; name="" } }
        END { if (name != "") print idx "\t" name "\t" desc }
    '
}

defaults_line() {
    pactl info 2>/dev/null | awk '
        /Default Sink:/   { printf "  default output:   %s\n", $3 }
        /Default Source:/ { printf "  default input:    %s\n", $3 }
    '
}

# Resolve a user reference (1-based position or literal name) to a device
# name from the given enumerate function. Echoes the name.
resolve() {
    # $1 = ref, $2 = enumerate function name
    local ref="$1" fn="$2" count=0 name
    case "$ref" in
        ''|*[!0-9]*)
            printf '%s' "$ref"
            return 0
            ;;
    esac
    while IFS=$'\t' read -r _ name _; do
        count=$((count + 1))
        if [ "$count" = "$ref" ]; then
            printf '%s' "$name"
            return 0
        fi
    done < <("$fn")
    echo "No device number $ref for that kind" >&2
    return 1
}

print_numbered() {
    local n=0
    while IFS=$'\t' read -r _ name desc; do
        n=$((n + 1))
        printf '  %d. %-32s %s\n' "$n" "$name" "$desc"
    done
}

# ------------------------------------------------------------------ command --
case "$COMMAND" in
    list)
        echo "Outputs (sinks):"
        enumerate_sinks | print_numbered
        echo "Inputs (sources):"
        enumerate_sources | print_numbered
        defaults_line
        echo
        echo "Switch with: $0 mic <n|name>   /   $0 out <n|name>"
        ;;

    mic|out)
        kind="$COMMAND"
        if [ $# -lt 1 ]; then
            if [ "$kind" = "mic" ]; then enumerate_sources | print_numbered
            else enumerate_sinks | print_numbered; fi
            defaults_line
            echo "Set one with: $0 $kind <n|name>"
            exit 0
        fi
        if [ "$kind" = "mic" ]; then
            target="$(resolve "$1" enumerate_sources)" || exit 1
            if pactl set-default-source "$target" 2>/dev/null; then
                echo "default input is now: $target"
            else
                echo "failed to set source '$target'" >&2
                exit 1
            fi
        else
            target="$(resolve "$1" enumerate_sinks)" || exit 1
            if pactl set-default-sink "$target" 2>/dev/null; then
                echo "default output is now: $target"
            else
                echo "failed to set sink '$target'" >&2
                exit 1
            fi
        fi
        defaults_line
        ;;

    alsa-sync)
        # Expose every source/sink as its own ALSA PCM so PortAudio apps can
        # choose devices directly. Needs write access to /etc/asound.conf,
        # i.e. run as root INSIDE the distro (ROOT_DIR for tests).
        ROOT_DIR="${ROOT_DIR:-}"
        conf="$ROOT_DIR/etc/asound.conf"
        begin="# BEGIN MovieTool device PCMs (generated - do not edit)"
        end="# END MovieTool device PCMs"

        tmp="$(mktemp "${TMPDIR:-/tmp}/movietool-asound.XXXXXX")"
        if [ -f "$conf" ]; then
            # Drop a previous generated block, keep everything else.
            awk -v b="$begin" -v e="$end" '
                index($0, b) == 1 { skip = 1; next }
                index($0, e) == 1 { skip = 0; next }
                !skip
            ' "$conf" > "$tmp"
        else
            mkdir -p "${conf%/*}" 2>/dev/null || true
        fi
        if ! grep -q "type pulse" "$tmp"; then
            cat >> "$tmp" <<'CONF'
# MovieTool: route every ALSA client to PulseAudio (see proot-audio-bridge.sh)
pcm.!default {
    type pulse
    fallback "sysdefault"
    hint {
        show on
    }
}
ctl.!default {
    type pulse
    fallback "sysdefault"
}
CONF
        fi

        slug() {
            printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]\+/_/g; s/^_//; s/_$//' | cut -c1-40
        }
        quote_safe() { printf '%s' "$1" | tr '"' "'"; }

        echo "$begin" >> "$tmp"
        added=0
        while IFS=$'\t' read -r _ name desc; do
            [ -n "$name" ] || continue
            printf 'pcm.mic_%s {\n    type pulse\n    source "%s"\n    hint { show on description "%s [mic]" }\n}\n' \
                "$(slug "$name")" "$name" "$(quote_safe "${desc:-$name}")" >> "$tmp"
            added=$((added + 1))
        done < <(enumerate_sources)
        while IFS=$'\t' read -r _ name desc; do
            [ -n "$name" ] || continue
            printf 'pcm.out_%s {\n    type pulse\n    sink "%s"\n    hint { show on description "%s [out]" }\n}\n' \
                "$(slug "$name")" "$name" "$(quote_safe "${desc:-$name}")" >> "$tmp"
            added=$((added + 1))
        done < <(enumerate_sinks)
        echo "$end" >> "$tmp"

        if cp "$tmp" "$conf" 2>/dev/null; then
            rm -f "$tmp"
            echo "wrote $added per-device ALSA entr(ies) into $conf"
            echo "Audacity: reload its device list (or restart it) - entries like"
            echo "  'mic_opensl_es_source [mic]' now appear next to 'default'."
        else
            rm -f "$tmp"
            echo "cannot write $conf - run as root inside the distro:" >&2
            echo "  proot-distro login archlinux -- /bin/bash -c 'bash /mnt/movietool-scripts/termux-audio-devices.sh alsa-sync'" >&2
            exit 1
        fi
        ;;

    help|--help|-h)
        usage 0
        ;;

    *)
        echo "unknown command: $COMMAND" >&2
        usage 2
        ;;
esac
