#!/usr/bin/env bash
# ============================================================================
# proot-lib.sh - shared helpers for the MovieTool proot fix scripts
# (sourced by proot-audio-bridge.sh and proot-desktop-fix.sh; not run directly)
#
# Both scripts accept ROOT_DIR=<dir> to run against a fake filesystem root
# (package installation is skipped) - used by the automated tests.
# ============================================================================

log()  { printf '[fix] %s\n' "$*"; }
fail() { printf '[fix] !! %s\n' "$*"; }

# Normalise optional variables once: the caller runs under `set -u`, and a
# single bare "$ROOT_DIR" aborted the whole fix (seen in the field: the
# Include-layout mirror loop died with 'ROOT_DIR: unbound variable' before
# the desktop crash could be fixed).
ROOT_DIR="${ROOT_DIR:-}"

# In ROOT_DIR test mode there is no real package manager to drive.
in_test_mode() {
    [ -n "${ROOT_DIR:-}" ]
}

# --- pacman_install begin (extracted verbatim by the test harness) ----------
# Full-upgrade install. Never 'pacman -Sy pkg': partial upgrades are what
# produce "libgcc ... exists in filesystem (owned by gcc-libs)" commit
# failures on Arch Linux ARM after the gcc-libs split.
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
    tail -15 "$pkglog" | sed 's/^/[fix]     /'
    rm -f "$pkglog"
    return 1
}
# --- pacman_install end -----------------------------------------------------

# Detect the distro's package manager ONCE. Termux's apt must never run: the
# proot PATH can leak Termux binaries, Termux apt refuses root ("Ability to
# run this command as root has been disabled permanently"), and the distro
# already has pacman. Order matters: pacman first, real apt second.
detect_package_manager() {
    if in_test_mode; then
        printf '%s' "${TEST_PM:-none}"
        return 0
    fi
    if command -v pacman >/dev/null 2>&1; then
        printf '%s' arch
        return 0
    fi
    local apt
    apt="$(command -v apt-get 2>/dev/null || true)"
    if [ -n "$apt" ] && case "$apt" in /data/data/*|/system/*) false ;; *) true ;; esac; then
        printf '%s' deb
        return 0
    fi
    printf '%s' none
}

# Installs packages with the distro's ONE package manager. Usage:
#   install_packages "arch:pkg1 pkg2" "deb:pkg3 pkg4"
# Only the group matching the detected manager is used - falling through to
# a second manager is exactly how Termux's apt got invoked inside Arch.
install_packages() {
    if in_test_mode; then
        log "ROOT_DIR test mode: skipping package installation"
        return 0
    fi
    local manager group distro pkgs found=0
    manager="$(detect_package_manager)"
    case "$manager" in
        arch)
            for group in "$@"; do
                distro="${group%%:*}"
                pkgs="${group#*:}"
                if [ "$distro" = "arch" ]; then
                    found=1
                    repair_arch_mirrors
                    pacman_install $pkgs || return 1
                fi
            done
            ;;
        deb)
            for group in "$@"; do
                distro="${group%%:*}"
                pkgs="${group#*:}"
                if [ "$distro" = "deb" ]; then
                    found=1
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get update -qq || true
                    apt-get install -y -qq $pkgs || { fail "apt failed for: $pkgs"; return 1; }
                fi
            done
            ;;
        none)
            fail "no usable package manager (pacman / apt-get) found in PATH"
            return 1
            ;;
    esac
    if [ "$found" = "0" ]; then
        fail "no package list for this distro's manager ($manager)"
        return 1
    fi
    return 0
}

# Point pacman at a MULTI-MIRROR Arch Linux ARM server set. Two field problems:
#   1. Mainline x86_64 mirrors (mirror.osbeck.com & co.) 404 on the ARM repos
#      (core/extra/community/alarm/aur).
#   2. Networks that break TLS to the geo mirror ("SSL: no alternative
#      certificate subject name matches target hostname"). A fallback on the
#      SAME host is not enough there, so every repo gets servers on several
#      INDEPENDENT hostnames: official country mirrors plus an unrelated
#      university mirror. pacman tries servers in order and verifies package
#      signatures either way, so one broken mirror cannot abort a sync.
# Handles BOTH layouts: inline "Server =" lines inside pacman.conf AND the
# "Include = /etc/pacman.d/mirrorlist" layout (ALARM images use either/both).
ALARM_MIRROR_SET=(
    'https://fr.mirror.archlinuxarm.org/$arch/$repo'                 # Paris
    'https://de.mirror.archlinuxarm.org/$arch/$repo'                 # Berlin
    'https://dk.mirror.archlinuxarm.org/$arch/$repo'                 # Aalborg (dotsrc)
    'https://nj.us.mirror.archlinuxarm.org/$arch/$repo'              # New Jersey
    'https://mirrors.tuna.tsinghua.edu.cn/archlinuxarm/$arch/$repo'  # independent host
    'https://mirror.archlinuxarm.org/$arch/$repo'                    # geo (broken TLS here)
    'http://mirror.archlinuxarm.org/$arch/$repo'                     # geo over http, last resort
)

_mirror_urls() {  # <file>: URLs of the active "Server =" lines
    grep -E '^[[:space:]]*Server[[:space:]]*=' "$1" 2>/dev/null |
        sed -E 's/^[[:space:]]*Server[[:space:]]*=[[:space:]]*//'
}

_mirror_alarm_hosts() {  # URLs on stdin: distinct ALARM-capable hostnames
    local u h
    while IFS= read -r u; do
        [ -n "$u" ] || continue
        h="$(printf '%s' "$u" | awk -F/ '{print tolower($3)}')"
        case "$u" in
            *//*.archlinuxarm.org/* | */archlinuxarm/*) printf '%s\n' "$h" ;;
        esac
    done | sort -u
}

_mirror_has_mainline() {  # URLs on stdin: success if a non-ALARM mirror is present
    local u
    while IFS= read -r u; do
        [ -n "$u" ] || continue
        case "$u" in
            *//*.archlinuxarm.org/* | */archlinuxarm/*) continue ;;
        esac
        return 0
    done
    return 1
}

_mirror_backup() {  # keep the original exactly once
    [ -e "$1.movietool.bak" ] || cp "$1" "$1.movietool.bak" 2>/dev/null || true
}

_mirror_pipe() { printf '%s|' "${ALARM_MIRROR_SET[@]}" | sed 's/|$//'; }

# Replace every active "Server =" line of a file with the full mirror set.
_mirror_replace_servers() {
    local f="$1" pipe
    pipe="$(_mirror_pipe)"
    awk -v ms="$pipe" '
        /^[[:space:]]*Server[[:space:]]*=/ {
            if (!done) { n = split(ms, M, "|"); for (i = 1; i <= n; i++) print "Server = " M[i]; done = 1 }
            next
        }
        { print }
    ' "$f" > "$f.tmp" && mv "$f.tmp" "$f"
}

# Append mirrors from the set that the file does not have yet (keeps existing
# entries; adds hostnames, which is what defeats a broken geo mirror).
_mirror_append_missing() {
    local f="$1" u added=0
    cp "$f" "$f.tmp"
    for u in "${ALARM_MIRROR_SET[@]}"; do
        if ! grep -qxF "Server = $u" "$f.tmp"; then
            printf 'Server = %s\n' "$u" >> "$f.tmp"
            added=1
        fi
    done
    if [ "$added" = "1" ]; then mv "$f.tmp" "$f"; else rm -f "$f.tmp"; fi
}

# One mirrorlist file: replace mainline sets, widen single-host ALARM sets.
_repair_mirror_file() {
    local f="$1" nalarm
    if _mirror_urls "$f" | _mirror_has_mainline; then
        _mirror_backup "$f"
        _mirror_replace_servers "$f"
        log "mirrorlist repaired: $f - now a multi-mirror ALARM set (backup saved)"
        return 0
    fi
    nalarm="$(_mirror_urls "$f" | _mirror_alarm_hosts | awk 'END { print NR }')"
    if [ "${nalarm:-0}" -lt 2 ]; then
        _mirror_backup "$f"
        _mirror_append_missing "$f"
        log "mirrorlist widened: $f - ALARM mirrors on several independent hosts (TLS to the geo mirror was rejected)"
        return 0
    fi
    return 1
}

# Inline "Server =" lines inside pacman.conf, per [repo] section. A section is
# left alone when its total pool (inline + include'd lists) already has >= 2
# distinct ALARM hosts and no mainline servers.
_repair_conf_inline() {
    local conf="$1" sec inc incf pool nalarm pipe changed=0
    pipe="$(_mirror_pipe)"
    while IFS= read -r sec; do
        [ -n "$sec" ] || continue
        pool="$(awk -F'[][]' -v s="$sec" '
            /^[[:space:]]*\[/ { cur = $2 }
            cur == s && /^[[:space:]]*Server[[:space:]]*=/ {
                sub(/^[[:space:]]*Server[[:space:]]*=[[:space:]]*/, ""); print
            }
        ' "$conf")"
        while IFS= read -r inc; do
            inc="${inc#*Include*=}"
            inc="$(printf '%s' "$inc" | tr -d ' ')"
            case "$inc" in
                /*) incf="${ROOT_DIR:-}$inc" ;;
                *)  incf="${ROOT_DIR:-}/etc/pacman.d/$(basename "$inc")" ;;
            esac
            if [ -f "$incf" ]; then
                pool+="$(_mirror_urls "$incf")"
                pool+=$'\n'
            fi
        done < <(grep -i "^[[:space:]]*Include *=" "$conf")
        nalarm="$(printf '%s\n' "$pool" | _mirror_alarm_hosts | awk 'END { print NR }')"
        if [ "${nalarm:-0}" -ge 2 ] && ! printf '%s\n' "$pool" | _mirror_has_mainline; then
            continue
        fi
        _mirror_backup "$conf"
        awk -F'[][]' -v s="$sec" -v ms="$pipe" '
            /^[[:space:]]*\[/ { cur = $2 }
            cur == s && /^[[:space:]]*Server[[:space:]]*=/ {
                if (!done) { n = split(ms, M, "|"); for (i = 1; i <= n; i++) print "Server = " M[i]; done = 1 }
                next
            }
            { print }
        ' "$conf" > "$conf.tmp" && mv "$conf.tmp" "$conf"
        changed=1
        log "pacman.conf [$sec]: inline Server lines replaced with a multi-mirror ALARM set (backup: pacman.conf.movietool.bak)"
    done < <(awk -F'[][]' '
        /^[[:space:]]*\[/ { cur = $2 }
        /^[[:space:]]*Server[[:space:]]*=/ { print cur }
    ' "$conf" | sort -u)
    [ "$changed" = "1" ] || return 1
    return 0
}

repair_arch_mirrors() {
    local conf
    conf="${ROOT_DIR:-}/etc/pacman.conf"
    [ -f "$conf" ] || return 0
    grep -q "^\[alarm\]" "$conf" || return 0   # only Arch Linux ARM has this repo

    local changed=0 line include list
    _repair_conf_inline "$conf" && changed=1
    while IFS= read -r line; do
        include="${line#*Include*=}"
        include="$(printf '%s' "$include" | tr -d ' ')"
        case "$include" in
            /*) list="${ROOT_DIR:-}$include" ;;
            *)  list="${ROOT_DIR:-}/etc/pacman.d/$(basename "$include")" ;;
        esac
        [ -f "$list" ] || continue
        _repair_mirror_file "$list" && changed=1
    done < <(grep -i "^[[:space:]]*Include *=" "$conf")

    if [ "$changed" = "1" ]; then
        log "pacman tries the servers in order, so one broken mirror no longer aborts the transaction"
    fi
    return 0
}
