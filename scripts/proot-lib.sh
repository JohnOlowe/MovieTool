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

# Point pacman back at the Arch Linux ARM mirrors. osbeck.com & co. are
# mainline x86_64 mirrors and 404 on the ARM repos (core/extra/alarm/aur).
repair_arch_mirrors() {
    local conf
    conf="${ROOT_DIR:-}/etc/pacman.conf"
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

# Installs packages with whichever package manager exists. Usage:
#   install_packages arch:pkg1,arch:pkg2 deb:pkg3,deb:pkg4
install_packages() {
    if in_test_mode; then
        log "ROOT_DIR test mode: skipping package installation"
        return 0
    fi
    local group distro pkgs
    for group in "$@"; do
        distro="${group%%:*}"
        pkgs="${group#*:}"
        case "$distro" in
            arch)
                if command -v pacman >/dev/null 2>&1; then
                    repair_arch_mirrors
                    pacman_install $pkgs || return 1
                fi
                ;;
            deb)
                if command -v apt-get >/dev/null 2>&1; then
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get update -qq || true
                    apt-get install -y -qq $pkgs || { fail "apt failed for: $pkgs"; return 1; }
                fi
                ;;
        esac
    done
    return 0
}
