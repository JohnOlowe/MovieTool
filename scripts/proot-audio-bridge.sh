#!/usr/bin/env bash
# ============================================================================
# proot-audio-bridge.sh - make ALSA (and therefore PortAudio) work inside a
# proot distro by routing ALSA's default device to PulseAudio.
#
# WHY THIS EXISTS
#   PortAudio V19.7.0-devel (what Audacity & co. bundle) only talks to ALSA
#   on Linux. Inside proot there are no ALSA cards - Android does not expose
#   /dev/snd to apps - so device lists come back EMPTY and apps fail with the
#   text of error code 0 ("Error ... 0 Success"), which really means
#   "no usable device". Reaching the Termux PulseAudio server is not enough;
#   ALSA must be TAUGHT to use it. That is what alsa-plugins' "pulse" PCM
#   does: ALSA default -> libpulse -> TCP 127.0.0.1 -> Termux PulseAudio
#   -> OpenSL ES -> phone hardware (speaker, headphones, BT) and microphone.
#
# FIELD NOTES (from a real Arch Linux ARM install, 2026-09):
#   * "error: failed to commit transaction (conflicting files) libgcc:
#     /usr/lib/libgcc_s.so exists in filesystem (owned by gcc-libs)"
#     -> caused by a PARTIAL upgrade ('pacman -Sy pkg' without -u) after the
#     gcc-libs/libgcc split. Fixed here by installing with -Syu (full system
#     upgrade in the same transaction), plus a targeted --overwrite retry as
#     an explicitly-labelled last resort.
#   * "failed retrieving file 'core.db' from mirror.osbeck.com : 404"
#     -> osbeck is a mainline x86_64 mirror; Arch Linux ARM (repos core,
#     extra, alarm, aur) needs mirror.archlinuxarm.org. Detected and repaired
#     automatically (your pacman.conf is backed up first).
#
# RUN AS ROOT INSIDE THE DISTRO. Easiest through the session launcher:
#     AUTO_FIX_BRIDGE=1 ./scripts/termux-session.sh      (run once)
# which binds this folder to /mnt/movietool-scripts inside the distro.
# Manually instead:
#     cp scripts/proot-audio-bridge.sh $TMPDIR/
#     proot-distro login archlinux --shared-tmp -- bash /tmp/proot-audio-bridge.sh
#
# Modes:  fix (default) - install packages and write the configs (idempotent)
#         check         - verify only; exit 0 when the bridge works
#
# Developers: set ROOT_DIR=<dir> to run against a fake filesystem root
# (skips package installation) - used by the automated tests.
# ============================================================================
set -u

MODE="${1:-fix}"
R="${ROOT_DIR:-}"

log()  { printf '[bridge] %s\n' "$*"; }
fail() { printf '[bridge] !! %s\n' "$*"; }

plugin_present() {
    local d
    for d in "$R/usr/lib/alsa-lib" \
             "$R/usr/lib/aarch64-linux-gnu/alsa-lib" \
             "$R/usr/lib/x86_64-linux-gnu/alsa-lib" \
             "$R/usr/lib/arm-linux-gnueabihf/alsa-lib" \
             "$R/usr/lib64/alsa-lib"; do
        [ -e "$d/libasound_module_pcm_pulse.so" ] && return 0
    done
    return 1
}

asound_ok() {
    [ -e "$R/etc/asound.conf" ] && grep -q "type pulse" "$R/etc/asound.conf" && plugin_present
}

pactl_works() {
    # In ROOT_DIR test mode there is no real server to probe: pretend success
    # so the config checks are what decide the result.
    [ -n "$R" ] && return 0
    command -v pactl >/dev/null 2>&1 || return 1
    PULSE_SERVER="${PULSE_SERVER:-127.0.0.1}" pactl info >/dev/null 2>&1
}

# --- pacman_install begin (extracted verbatim by the test harness) ----------
# Full-upgrade install. Never 'pacman -Sy pkg': partial upgrades are what
# produced the "libgcc ... owned by gcc-libs" commit failure.
pacman_install() {
    local pkglog
    pkglog="$(mktemp "${TMPDIR:-/tmp}/movietool-pacman.XXXXXX")"
    log "running: pacman -Syu --needed --noconfirm $*"
    log "(this upgrades the whole system first - required, partial upgrades break Arch)"
    pacman -Syu --needed --noconfirm "$@" 2>&1 | tee "$pkglog"
    if [ "${PIPESTATUS[0]}" -eq 0 ]; then
        rm -f "$pkglog"
        return 0
    fi
    if grep -q "exists in filesystem" "$pkglog"; then
        local -a overwrites=()
        local p
        while IFS= read -r p; do
            overwrites+=("--overwrite=$p")
        done < <(awk '/exists in filesystem/ {print $2}' "$pkglog" | sort -u)
        if [ "${#overwrites[@]}" -gt 0 ]; then
            fail "conflicting files; retrying with --overwrite (last resort):"
            for p in "${overwrites[@]}"; do fail "    $p"; done
            pacman -Syu --needed --noconfirm "${overwrites[@]}" "$@" 2>&1 | tee -a "$pkglog"
            if [ "${PIPESTATUS[0]}" -eq 0 ]; then
                rm -f "$pkglog"
                return 0
            fi
        fi
    fi
    fail "pacman output (last lines):"
    tail -15 "$pkglog" | sed 's/^/[bridge]     /'
    rm -f "$pkglog"
    return 1
}
# --- pacman_install end -----------------------------------------------------

# Point pacman back at the Arch Linux ARM mirrors. osbeck.com & co. are
# mainline x86_64 mirrors and 404 on the ARM repos (core/extra/alarm/aur).
repair_arch_mirrors() {
    local conf="$R/etc/pacman.conf"
    [ -f "$conf" ] || return 0
    grep -q "^\[alarm\]" "$conf" || return 0   # only Arch Linux ARM has this repo
    if grep -E "^Server *=" "$conf" | grep -qv "archlinuxarm\.org"; then
        cp "$conf" "$conf.movietool.bak" 2>/dev/null || true
        awk '/^Server *=/ { print "Server = https://mirror.archlinuxarm.org/$arch/$repo"; next } { print }' \
            "$conf" > "$conf.tmp" && mv "$conf.tmp" "$conf"
        log "pacman mirrors repaired -> mirror.archlinuxarm.org (backup: pacman.conf.movietool.bak)"
        log "the previous mirror 404'd on every database (it is not an ARM mirror)"
    fi
    return 0
}

case "$MODE" in
    check)
        if asound_ok && pactl_works; then
            log "bridge OK: ALSA default -> PulseAudio (PortAudio will list devices)"
            exit 0
        fi
        asound_ok || log "missing: /etc/asound.conf routing to pulse, or the pulse PCM plugin"
        pactl_works || log "cannot reach PulseAudio on 127.0.0.1 (is Termux pulseaudio running?)"
        exit 1
        ;;
    fix)
        ;;
    *)
        printf 'usage: %s [fix|check]\n' "$0" >&2
        exit 2
        ;;
esac

# ------------------------------------------------------------- 1. packages --
if [ -n "$R" ]; then
    log "ROOT_DIR test mode: skipping package installation"
elif command -v pacman >/dev/null 2>&1; then
    log "installing: alsa-lib alsa-plugins libpulse alsa-utils pulseaudio (Arch)"
    repair_arch_mirrors
    if pacman_install alsa-lib alsa-plugins libpulse alsa-utils pulseaudio; then
        log "packages installed"
    else
        fail "automatic install failed. Manual fix inside the distro:"
        if [ -f /etc/pacman.conf ] && grep -q "^\[alarm\]" /etc/pacman.conf; then
            fail "  sudo sed -i 's|^Server.*|Server=https://mirror.archlinuxarm.org/\$arch/\$repo|' /etc/pacman.conf"
        fi
        fail "  sudo pacman -Syu"
        fail "  sudo pacman -S alsa-lib alsa-plugins libpulse alsa-utils pulseaudio"
    fi
elif command -v apt-get >/dev/null 2>&1; then
    log "installing: libasound2-plugins pulseaudio-utils alsa-utils (Debian/Ubuntu)"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq || true
    apt-get install -y -qq libasound2-plugins pulseaudio-utils alsa-utils || \
        fail "apt failed - install the packages above manually"
else
    fail "unknown package manager; install 'alsa-plugins' (the PCM 'pulse') and"
    fail "the pulseaudio client tools with your distro's package manager"
fi

# ------------------------------------------------------- 2. /etc/asound.conf --
mkdir -p "$R/etc"
if [ -e "$R/etc/asound.conf" ] && grep -q "type pulse" "$R/etc/asound.conf"; then
    log "/etc/asound.conf already routes to PulseAudio"
else
    if [ -e "$R/etc/asound.conf" ]; then
        cp "$R/etc/asound.conf" "$R/etc/asound.conf.movietool.bak"
        log "backed up the old /etc/asound.conf"
    fi
    cat > "$R/etc/asound.conf" <<'CONF'
# MovieTool: route every ALSA client (PortAudio, aplay, browsers, ...)
# to PulseAudio. Inside proot there is no /dev/snd; without this file
# ALSA has no devices at all and PortAudio apps show empty device lists.
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
    log "wrote $R/etc/asound.conf"
fi

# ------------------------------------------- 3. /etc/pulse/client.conf -------
# Belt and braces: even if a shell loses PULSE_SERVER (su -, cron, ...),
# libpulse still knows where the server is, and it must never autospawn a
# second, device-less PulseAudio inside the distro.
mkdir -p "$R/etc/pulse"
CC="$R/etc/pulse/client.conf"
if [ -e "$CC" ] && grep -q "^default-server" "$CC"; then
    log "/etc/pulse/client.conf already points at the server"
else
    {
        echo "# MovieTool: talk to the PulseAudio server in Termux;"
        echo "# never spawn a local (device-less) daemon."
        echo "default-server = 127.0.0.1"
        echo "autospawn = no"
    } >> "$CC"
    log "updated $CC (default-server=127.0.0.1, autospawn=no)"
fi

# ------------------------------------------------------------ 4. verify ------
log "verification:"
if asound_ok; then
    log "  asound.conf -> pulse plugin: OK"
else
    fail "  asound.conf/pulse plugin NOT in place - PortAudio will still see no devices"
fi
if pactl_works; then
    log "  PulseAudio server reachable: OK"
    if [ -z "$R" ] && command -v pactl >/dev/null 2>&1; then
        pactl info 2>/dev/null | grep -E "Server Name|Default Sink|Default Source" | sed 's/^/[bridge]      /'
    fi
else
    fail "  cannot reach PulseAudio on 127.0.0.1 - start the session with scripts/termux-session.sh first"
fi
if command -v aplay >/dev/null 2>&1; then
    log "  ALSA PCM list (what PortAudio scans):"
    aplay -L 2>/dev/null | grep -E "^(default|pulse|sysdefault)" | sed 's/^/[bridge]      /'
fi
log "done. For the microphone run scripts/termux-audio-test.sh inside the session."

# Non-zero when the bridge is still not usable, so callers (termux-session.sh)
# do not cache a broken install as a success.
asound_ok && pactl_works
