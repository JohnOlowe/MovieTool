#!/usr/bin/env bash
# ============================================================================
# proot-desktop-fix.sh - stop GTK from aborting XFCE4 inside proot
#
# THE CRASH ("the environment doesn't launch")
#   Bail out! Gtk:ERROR: gtkiconhelper.c:495: ensure_surface_for_gicon:
#   Failed to load .../image-missing.svg: Loader process exited early
#
#   gdk-pixbuf 2.44+ / GTK load SVGs through the new glycin loaders, which
#   sandbox every decode with bubblewrap --unshare-all. Inside proot there
#   are no Linux namespaces, bwrap fails, the loader dies, and GTK treats a
#   failed icon load as fatal - so xfce4-session aborts on the FIRST icon.
#   Same bug: termux/proot-distro#644 (closed after proot itself learned to
#   emulate namespaces, termux/proot#359).
#
# THE FIXES APPLIED HERE (as root inside the distro):
#   1. Install librsvg (its classic in-process gdk-pixbuf SVG loader) and
#      dbus (dbus-launch, for the session bus Arch's autolaunch can't
#      create - it is disabled at compile time).
#   2. If bubblewrap cannot work here (probed directly), hide the glycin
#      loader configs (renamed *.movietool-disabled) so gdk-pixbuf falls
#      back to librsvg's loader. If bwrap works, glycin is left alone.
#   3. Regenerate the gdk-pixbuf loader cache.
#
# NOTE: upgrading proot on the Termux side (pkg upgrade proot) fixes the
# bwrap/namespace problem upstream; this script is the fallback that keeps
# the session launchable regardless.
#
# RUN AS ROOT INSIDE THE DISTRO - usually via the session launcher:
#     AUTO_FIX_DESKTOP=1 ./scripts/termux-session.sh     (run once)
# Modes: fix (default) | check.  ROOT_DIR=<dir> for the test harness.
# ============================================================================
set -u

MODE="${1:-fix}"
R="${ROOT_DIR:-}"
# shellcheck source=proot-lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)/proot-lib.sh"

# ------------------------------------------------------------- probing ------

# Can bubblewrap actually run here? proot has no user namespaces, so
# --unshare-all normally fails. In ROOT_DIR test mode the caller decides
# via BWRAP_WORKS=0/1 (default: broken, the proot situation).
bwrap_works() {
    if in_test_mode; then
        [ "${BWRAP_WORKS:-0}" = "1" ]
        return
    fi
    command -v bwrap >/dev/null 2>&1 || return 1
    bwrap --unshare-all --ro-bind / / /bin/true >/dev/null 2>&1
}

# Path of the glycin loader configuration (glob: the compat version segment
# of the directory name varies between releases).
glycin_conf_dir() {
    local d
    for d in "$R"/usr/share/glycin-loaders*; do
        case "$d" in
            *.movietool-disabled*) continue ;;
        esac
        [ -d "$d" ] && { printf '%s' "$d"; return 0; }
    done
    return 1
}

glycin_disabled_marker() {
    local d
    for d in "$R"/usr/share/glycin-loaders*.movietool-disabled; do
        [ -d "$d" ] && return 0
    done
    return 1
}

svg_loader_present() {
    local f
    for f in "$R"/usr/lib/gdk-pixbuf-2.0/*/loaders/libpixbufloader-svg.so \
             "$R"/usr/lib/*/gdk-pixbuf-2.0/*/loaders/libpixbufloader-svg.so; do
        [ -e "$f" ] && return 0
    done
    return 1
}

dbus_launch_present() {
    [ -n "$R" ] && return 0   # not checked in test mode
    command -v dbus-launch >/dev/null 2>&1
}

svg_path_ok() {
    if glycin_conf_dir >/dev/null; then
        bwrap_works   # glycin active: only fine when its sandbox works
    else
        # glycin absent or disabled: the classic loader must be there.
        svg_loader_present
    fi
}

report_status() {
    if glycin_conf_dir >/dev/null; then
        log "glycin (sandboxed) SVG loader: active"
        bwrap_works && log "  bubblewrap probe: OK" || fail "  bubblewrap probe: FAILED (namespaces unavailable under proot)"
    elif glycin_disabled_marker; then
        log "glycin SVG loader: disabled by MovieTool (classic loader in use)"
    else
        log "glycin SVG loader: not installed"
    fi
    svg_loader_present && log "  librsvg SVG loader: present" || fail "  librsvg SVG loader: MISSING"
    dbus_launch_present && log "  dbus-launch: present" || fail "  dbus-launch: MISSING"
}

case "$MODE" in
    check)
        if svg_path_ok && dbus_launch_present; then
            log "desktop OK: SVG loading and the D-Bus session bus will work"
            exit 0
        fi
        svg_path_ok || fail "SVG loading is broken (the exact cause of the Gtk 'Bail out!' crash)"
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

# ------------------------------------------------- 1. packages --------------
log "installing librsvg + dbus (classic SVG loader, session bus)"
install_packages \
    "arch:librsvg dbus" \
    "deb:librsvg2-common dbus dbus-x11" || \
    fail "package install failed; install 'librsvg' and 'dbus' manually"

# ------------------------------------------------- 2. glycin ----------------
if glycin_conf_dir >/dev/null; then
    CONFDIR="$(glycin_conf_dir)"
    if bwrap_works; then
        log "bubblewrap works here - leaving the sandboxed glycin loaders enabled"
    else
        mv "$CONFDIR" "$CONFDIR.movietool-disabled"
        log "disabled $CONFDIR (renamed .movietool-disabled)"
        log "  gdk-pixbuf now falls back to librsvg's classic in-process SVG loader"
    fi
elif glycin_disabled_marker; then
    log "glycin loaders already disabled by MovieTool"
else
    log "no glycin loaders installed - nothing to disable"
fi

# ------------------------------------------------- 3. loader cache ----------
if [ -z "$R" ] && command -v gdk-pixbuf-query-loaders >/dev/null 2>&1; then
    gdk-pixbuf-query-loaders --update-cache >/dev/null 2>&1 || \
        gdk-pixbuf-query-loaders > "$(command -v gdk-pixbuf-query-loaders >/dev/null 2>&1 && ls /usr/lib/gdk-pixbuf-2.0/*/loaders.cache 2>/dev/null | head -1)" 2>/dev/null || true
    log "gdk-pixbuf loader cache refreshed"
fi

# ------------------------------------------------- 4. verify ----------------
log "verification:"
report_status
if svg_path_ok && dbus_launch_present; then
    log "desktop fix: OK - start the session normally"
    exit 0
fi
fail "desktop fix incomplete - see the !! lines above"
fail "also try updating proot itself (Termux):  pkg upgrade proot"
exit 1
