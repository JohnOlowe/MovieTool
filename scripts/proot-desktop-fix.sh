#!/usr/bin/env bash
# ============================================================================
# proot-desktop-fix.sh - stop GTK from aborting XFCE4 inside proot
#
# THE CRASH
#   Bail out! Gtk:ERROR: gtkiconhelper.c:495: ensure_surface_for_gicon:
#   Failed to load .../image-missing.svg: Loader process exited early ...
#   Command: "bwrap" "--unshare-all" ...
#
#   gdk-pixbuf 2.44+ / GTK load ALL images through the sandboxed glycin
#   loaders, which shell out to bubblewrap --unshare-all. proot has no
#   namespaces and (old proot) no mount emulation, bwrap dies, and GTK
#   treats a failed icon load as fatal. Disabling glycin is NOT an option
#   on 2.44: the classic loader system was removed, so with glycin hidden
#   GTK fails with "No image loaders are configured" - even with librsvg
#   installed. Same bug: termux/proot-distro#644 (bwrap support landed in
#   proot itself, termux/proot#359 - so `pkg upgrade proot` in Termux is
#   the proper cure and makes the native sandbox work again).
#
# THE FIX APPLIED HERE (as root inside the distro):
#   1. Restore the glycin loader configs if a previous MovieTool run hid
#      them (renamed *.movietool-disabled).
#   2. Probe bwrap (--unshare-all really). Works? glycin runs natively.
#   3. Broken? install a bwrap SHIM at /usr/local/bin/bwrap that parses
#      bwrap's command line and execs the loader directly. The loader then
#      runs unsandboxed - inside proot the sandbox adds nothing anyway;
#      the whole distro is a chroot. /usr/local/bin precedes /usr/bin in
#      PATH, the real bwrap stays untouched at /usr/bin/bwrap.
#   4. dbus (Arch compiles out D-Bus autolaunch; XFCE needs dbus-launch).
#
# RUN AS ROOT INSIDE THE DISTRO - usually via the session launcher:
#     AUTO_FIX_DESKTOP=1 ./scripts/termux-session.sh     (run once)
# Modes: fix (default) | check.  ROOT_DIR=<dir>/BWRAP_WORKS=0 for tests.
# ============================================================================
set -u

MODE="${1:-fix}"
R="${ROOT_DIR:-}"
# shellcheck source=proot-lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)/proot-lib.sh"

SHIM_PATH="$R/usr/local/bin/bwrap"

# ------------------------------------------------------------- probing ------

# Does the bwrap that PATH resolves to actually work here? In ROOT_DIR test
# mode the caller decides via BWRAP_WORKS (default: broken, the proot
# situation); a MovieTool shim counts as working, which is its whole job.
bwrap_works() {
    if in_test_mode; then
        if [ "${BWRAP_WORKS:-0}" = "1" ]; then return 0; fi
        [ -e "$R/usr/local/bin/bwrap" ]
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
        case "$d" in *.movietool-disabled*) continue ;; esac
        [ -d "$d" ] && { printf '%s' "$d"; return 0; }
    done
    return 1
}

glycin_disabled_dir() {
    local d
    for d in "$R"/usr/share/glycin-loaders*.movietool-disabled; do
        [ -d "$d" ] && { printf '%s' "$d"; return 0; }
    done
    return 1
}

dbus_launch_present() {
    [ -n "$R" ] && return 0   # not checked in test mode
    command -v dbus-launch >/dev/null 2>&1
}

# The whole SVG pipeline is fine when glycin configs exist and the effective
# bwrap works (native or shimmed).
svg_path_ok() {
    glycin_conf_dir >/dev/null && bwrap_works
}

case "$MODE" in
    check)
        if svg_path_ok && dbus_launch_present; then
            log "desktop OK: SVG loading and the D-Bus session bus will work"
            exit 0
        fi
        glycin_conf_dir >/dev/null || fail "glycin loader configs missing - GTK has no image loaders at all"
        svg_path_ok || fail "bwrap does not work under proot and no shim is installed (the 'Bail out!' crash)"
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

# ------------------------------------------------- 1. glycin configs --------
# Always present - hiding them leaves GTK with zero loaders on 2.44+.
if glycin_conf_dir >/dev/null; then
    log "glycin loader configs: present"
elif DIR="$(glycin_disabled_dir)"; then
    NEWDIR="${DIR%.movietool-disabled}"
    if [ -e "$NEWDIR" ]; then
        fail "cannot restore $DIR: $NEWDIR already exists"
    else
        mv "$DIR" "$NEWDIR"
        log "restored glycin loader configs: $NEWDIR (hiding them left GTK without any image loader)"
    fi
else
    log "no glycin loader configs found - if SVG still fails, install the 'glycin-loaders' package"
fi

# ------------------------------------------------- 2. bwrap shim ------------
if bwrap_works; then
    log "bubblewrap probe: OK - glycin runs natively (recent proot, namespaces emulated)"
    if [ -e "$SHIM_PATH" ]; then
        rm -f "$SHIM_PATH"
        log "removed the MovieTool bwrap shim (no longer needed)"
    fi
else
    log "bubblewrap probe: FAILED (proot has no namespaces/mount emulation)"
    mkdir -p "${SHIM_PATH%/*}"
    cat > "$SHIM_PATH" <<'SHIM'
#!/usr/bin/env bash
# MovieTool bwrap shim for proot (generated by proot-desktop-fix.sh).
#
# glycin/gdk-pixbuf 2.44+ sandbox image loaders with bubblewrap, which needs
# Linux namespaces and mount() - unavailable inside proot, so every image
# load dies and GTK aborts ("Bail out! ... image-missing.svg"). This shim
# understands bwrap's command line, ignores the sandbox setup and execs the
# wrapped command directly. Under proot the sandbox is meaningless anyway:
# the entire distro already runs inside a chroot.
#
# bwrap grammar used here (all current glycin calls):
#   [options...] COMMAND [args...]
#   options: --flag | --opt ARG | --opt ARG1 ARG2   (values never start with -)
set -u

shim_warn() { printf 'bwrap-shim: %s\n' "$*" >&2; }

args=("$@")
i=0
n=${#args[@]}
while [ "$i" -lt "$n" ]; do
    a="${args[$i]}"
    case "$a" in
        # ---- options taking NO argument
        --unshare-all|--unshare-user|--unshare-user-try|--unshare-ipc|--unshare-pid|--unshare-net|--unshare-netns|--unshare-uts|--unshare-cgroup|--unshare-cgroup-try|--die-with-parent|--clearenv|--new-session|--as-pid-1)
            i=$((i + 1))
            ;;
        # ---- options taking ONE argument
        --chdir|--proc|--dev|--tmpfs|--seccomp|--size|--perms|--sync-fd|--args|--cap-add|--cap-drop|--lock-file|--snippet-fd)
            i=$((i + 2))
            ;;
        # ---- options taking TWO arguments
        --bind|--ro-bind|--dev-bind|--bind-try|--ro-bind-try|--dev-bind-try|--setenv|--unsetenv|--symlink|--file|--bind-data|--ro-bind-data)
            i=$((i + 3))
            ;;
        # ---- end of options: everything after -- is the command
        --)
            i=$((i + 1))
            exec "${args[@]:$i}"
            ;;
        -*)
            shim_warn "unknown option '$a' - please report this; bwrap's option set changed"
            exit 64
            ;;
        *)
            # First non-option token: this is COMMAND. exec keeps open fds,
            # which matters because glycin passes --dbus-fd/--seccomp fds.
            exec "${args[@]:$i}"
            ;;
    esac
done
shim_warn "no command found in arguments - nothing to do"
exit 64
SHIM
    chmod 755 "$SHIM_PATH"
    log "installed bwrap shim: $SHIM_PATH (real bwrap untouched at /usr/bin/bwrap)"
    if bwrap_works; then
        log "shim probe: OK - image loaders will run (unsandboxed, which is correct under proot)"
    else
        fail "shim did not take effect - check that PATH has /usr/local/bin before /usr/bin"
    fi
fi

# ------------------------------------------------- 3. packages --------------
log "installing dbus (session bus; librsvg is no longer needed - 2.44+ has no classic loaders)"
install_packages "arch:dbus" "deb:dbus dbus-x11" || \
    fail "package install failed; install 'dbus' manually"

# ------------------------------------------------- 4. verify ----------------
log "verification:"
if glycin_conf_dir >/dev/null; then
    log "  glycin loader configs: present"
else
    fail "  glycin loader configs: MISSING"
fi
if bwrap_works; then
    log "  bwrap (effective): OK"
else
    fail "  bwrap (effective): still broken"
fi
if dbus_launch_present; then
    log "  dbus-launch: present"
else
    fail "  dbus-launch: MISSING"
fi
if svg_path_ok && dbus_launch_present; then
    log "desktop fix: OK - start the session normally"
    exit 0
fi
fail "desktop fix incomplete - see the !! lines above"
fail "also try updating proot itself (Termux):  pkg upgrade proot"
exit 1
