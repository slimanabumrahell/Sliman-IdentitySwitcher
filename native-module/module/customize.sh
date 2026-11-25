#!/system/bin/sh
# Magisk / KernelSU / APatch install script (runs after the zip is unpacked into $MODPATH).
ui_print "- Sliman Native Hooks"
ui_print "  Needs Zygisk enabled (Magisk) or a Zygisk provider (ZygiskNext / ReZygisk)."
ui_print "  Open Sliman-Identity, save a profile, then force-stop the target app."

touch "$MODPATH/profiles.conf"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/profiles.conf" 0 0 0644
