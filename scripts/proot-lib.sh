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

# Point pacman back at the Arch Linux ARM mirrors. Mainline x86_64 mirrors
# (mirror.osbeck.com & co.) 404 on the ARM repos (core/extra/alarm/aur).
# Handles BOTH layouts: inline "Server =" lines in pacman.conf AND the
# "Include = /etc/pacman.d/mirrorlist" layout the proot images ship.
# Each repo gets an HTTPS entry AND an HTTP fallback: some networks break
# TLS to the geo mirror ("SSL: no alternative certificate subject name
# matches target hostname"); pacman moves to the next server on failure,
# and package integrity is signature-checked either way.
repair_arch_mirrors() {
    local conf
    conf="${ROOT_DIR:-}/etc/pacman.conf"
    [ -f "$conf" ] || return 0
    grep -q "^\[alarm\]" "$conf" || return 0   # only Arch Linux ARM has this repo

    local fixed=0 line include list
    # 1. Inline Server lines in pacman.conf itself.
    if grep -E "^[[:space:]]*Server *=" "$conf" | grep -qv "archlinuxarm\.org"; then
        [ -e "$conf.movietool.bak" ] || cp "$conf" "$conf.movietool.bak" 2>/dev/null || true
        awk '/^[[:space:]]*Server *=/ {
            print "Server = https://mirror.archlinuxarm.org/$arch/$repo";
            print "Server = http://mirror.archlinuxarm.org/$arch/$repo";
            next
        } { print }' "$conf" > "$conf.tmp" && mv "$conf.tmp" "$conf"
        fixed=1
        log "pacman.conf Server lines repointed to mirror.archlinuxarm.org (backup: pacman.conf.movietool.bak)"
    fi
    # 2. Include'd mirrorlist files ("Server = ..." lines work there too).
    while IFS= read -r line; do
        include="${line#*Include*=}"
        include="$(printf '%s' "$include" | tr -d ' ')"
        case "$include" in
            /*) list="${ROOT_DIR:-}$include" ;;
            *)  list="${ROOT_DIR:-}/etc/pacman.d/$(basename "$include")" ;;
        esac
        [ -f "$list" ] || continue
        if grep -E "^[[:space:]]*Server *=" "$list" | grep -qv "archlinuxarm\.org"; then
            [ -e "$list.movietool.bak" ] || cp "$list" "$list.movietool.bak" 2>/dev/null || true
            awk '/^[[:space:]]*Server *=/ {
                print "Server = https://mirror.archlinuxarm.org/$arch/$repo";
                print "Server = http://mirror.archlinuxarm.org/$arch/$repo";
                next
            } { print }' "$list" > "$list.tmp" && mv "$list.tmp" "$list"
            fixed=1
            log "mirrorlist repaired: $list (backup saved, HTTPS + HTTP fallback)"
        elif grep -q "https://mirror.archlinuxarm.org" "$list" && \
             ! grep -q "http://mirror.archlinuxarm.org" "$list"; then
            # ALARM already set, HTTPS only: add the HTTP fallback for
            # networks that break TLS to the geo mirror.
            [ -e "$list.movietool.bak" ] || cp "$list" "$list.movietool.bak" 2>/dev/null || true
            awk '/^[[:space:]]*Server *= *https:\/\/mirror\.archlinuxarm\.org/ {
                print;
                print "Server = http://mirror.archlinuxarm.org/$arch/$repo";
                next
            } { print }' "$list" > "$list.tmp" && mv "$list.tmp" "$list"
            fixed=1
            log "added HTTP fallback mirror to $list (TLS to the geo mirror was rejected)"
        fi
    done < <(grep -i "^[[:space:]]*Include *=" "$conf")

    if [ "$fixed" = "1" ]; then
        log "pacman tries the servers in order, so a broken one no longer aborts the transaction"
    fi
    return 0
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

# Point pacman back at the Arch Linux ARM mirrors. Mainline x86_64 mirrors
# (mirror.osbeck.com & co.) 404 on the ARM repos (core/extra/alarm/aur).
# Handles BOTH layouts: inline "Server =" lines in pacman.conf AND the
# "Include = /etc/pacman.d/mirrorlist" layout the proot images ship.
