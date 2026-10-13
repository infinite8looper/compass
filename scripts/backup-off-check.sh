#!/bin/zsh
# Usage: scripts/backup-off-check.sh
# Checks, on the attached emulator and with the debug app as it is built (app/build/outputs/apk/debug/app-debug.apk),
# what the app's backup agent hands the system's backup and what a restore leaves, by running the system's own
# backup and restore (`bmgr`) against the system's local transport and reading what the transport then holds. The
# local transport says what kind of pass it is through its own parameter
# (`settings put secure backup_local_transport_parameters ...`): nothing (a backup that is not encrypted),
# `is_encrypted=true` (an encrypted backup) or `is_device_transfer=true` (a transfer to another device). Under the
# last two the backup rules let the database and the settings file through, so "the marker alone" is judged there.
# Every numbered case is judged; the cases, in the order they run:
#   1.  switch on, not encrypted: the pass must not succeed and the transport must hold nothing.
#   2.  switch on, encrypted: success, the archive holds the database, the waypoint's name is in it.
#   2b. switch on, a transfer: the same.
#   3.  the switch turned off through the app's question; not encrypted: success, the archive holds the manifest and
#       the marker `backup-switched-off` and nothing else, the name is not in it, no marker is left in the app's files.
#   3e. the same switch, encrypted: the same archive, the name not in it.
#   3t. the same switch, a transfer: the same archive, the name not in it.
#   4t. the app uninstalled and installed again from that transfer: the switch is off, turned off by the wearer, with
#       no question asked; no database came back; no record is left.
#   4.  a backup (not encrypted) and the app installed again from it: the same; then a waypoint is added and the next
#       backup, encrypted, holds the marker alone.
#   5.  the switch tapped on (no question), an encrypted backup, the app installed again: the waypoint is back and
#       the switch is on.
#   6.  with the switch on, a record that a restore owes the switch off is put in the app's files and an encrypted
#       backup is run without the app having been started: the marker alone, the record gone, the switch off.
#   7.  the switch on again; the settings folder is made to take no write (`chmod 500`) and the marker's backup is
#       restored over the app (`bmgr restore`): the record is there and the stored switch still says on; an encrypted
#       backup holds the marker alone and the record is still there; the app shows the switch off; with the folder
#       writable again and the app started, the switch is stored off, turned off by the wearer, and the record is gone.
#   8.  the switch on; the app's files folder is made to take no write: an encrypted backup hands over nothing.
# Exit status 0 if every requirement holds, 1 otherwise.
# It wipes the app's data (it installs the app afresh several times), puts a waypoint in its database with the
# device's sqlite3, switches the Backup Manager on with the local transport and empties that transport, and reads
# the transport's files as root. All of that is put back when it ends, and when it is stopped by INT, TERM or HUP: the transport and the Backup
# Manager as they were, the transport's parameter removed, adbd not rooted, the app installed with its data cleared.
# Emulators only (scripts/emulator-only.sh), and only images with `adb root` and sqlite3: API 33, 34 and 35.
source "$(dirname "$0")/emulator-only.sh" || exit 1
ADB=~/Library/Android/sdk/platform-tools/adb
PKG=com.infinite8looper.compass
LT=com.android.localtransport/.LocalTransport
NAME=CHECKWAYPOINT
cd "$(dirname "$0")/.." || exit 1
APK=app/build/outputs/apk/debug/app-debug.apk
if [ ! -f $APK ]; then echo "no $APK: build it with ./gradlew assembleDebug" >&2; exit 1; fi
if ! $ADB shell "which sqlite3" 2>/dev/null | grep -q sqlite3; then echo "this image has no sqlite3: use the API 33, 34 or 35 emulator" >&2; exit 1; fi
$ADB root > /dev/null 2>&1; sleep 3
if [ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" != "0" ]; then echo "this image has no adb root: use the API 33, 34 or 35 emulator" >&2; exit 1; fi
$ADB unroot > /dev/null 2>&1; sleep 3

was_enabled=$($ADB shell bmgr enabled | tr -d '\r')
was_transport=$($ADB shell bmgr list transports | tr -d '\r' | sed -n 's/^ *\* //p')
was_params=$($ADB shell settings get secure backup_local_transport_parameters | tr -d '\r')
put_back() {
  $ADB unroot > /dev/null 2>&1; sleep 2
  if [ "$was_params" = "null" ] || [ -z "$was_params" ]; then $ADB shell settings delete secure backup_local_transport_parameters > /dev/null
  else $ADB shell settings put secure backup_local_transport_parameters "$was_params"; fi
  $ADB shell bmgr init $LT > /dev/null 2>&1
  [ -n "$was_transport" ] && $ADB shell bmgr transport $was_transport > /dev/null 2>&1
  case "$was_enabled" in *disabled*) $ADB shell bmgr enable false > /dev/null ;; esac
  $ADB shell "run-as $PKG chmod 700 files files/datastore" > /dev/null 2>&1
  $ADB shell pm clear $PKG > /dev/null 2>&1
  echo "put back: $($ADB shell bmgr enabled | tr -d '\r'); transport $($ADB shell bmgr list transports | tr -d '\r' | sed -n 's/^ *\* //p'); transport parameters $($ADB shell settings get secure backup_local_transport_parameters | tr -d '\r'); adbd uid $($ADB shell id -u | tr -d '\r')"
}
done_put_back=0
finish() { if [ $done_put_back -eq 0 ]; then done_put_back=1; put_back; fi }
trap 'finish; exit 1' INT TERM HUP
trap 'finish' EXIT

bad=0
need() { if eval "$2"; then echo "  ok: $1"; else echo "  NOT SO: $1"; bad=1; fi; }
backup() { $ADB shell bmgr backupnow $PKG 2>&1 | tr -d '\r' | sed -n "s/.*Package $PKG with result: //p" | tail -1; }
archive=""; entries=""; names=0
read_transport() {
  $ADB root > /dev/null; sleep 3
  archive=$($ADB shell "find /data/data/com.android.localtransport -type f 2>/dev/null | grep $PKG" | tr -d '\r' | tr '\n' ' ')
  if [ -n "${archive// /}" ]; then
    # The archive is a tar: its entries are listed by the device's tar, every one of them.
    entries=$($ADB shell "for f in $archive; do tar -tf \$f; done | sort -u | tr '\n' ' '" | tr -d '\r')
    names=$($ADB shell "cat $archive | grep -c -a $NAME" | tr -d '\r')
  else entries=""; names=0; fi
  $ADB unroot > /dev/null; sleep 3
  echo "  the transport holds: ${entries:-nothing}; '$NAME' in it: $names time(s)"
}
# Where the `Back up waypoints` row is and whether it is checked, as the accessibility tree has it: "x y true|false",
# or nothing when the row is not on screen.
switch_row() {
  $ADB exec-out uiautomator dump /dev/tty 2>/dev/null | python3 -c '
import re, sys
import xml.etree.ElementTree as ET
doc = sys.stdin.read()
if "<hierarchy" not in doc: sys.exit(0)
root = ET.fromstring(doc[doc.index("<hierarchy"):doc.rindex("</hierarchy>") + len("</hierarchy>")])
parent = {c: p for p in root.iter() for c in p}
for n in root.iter("node"):
    if n.get("text") == "Back up waypoints":
        l, t, r, b = map(int, re.findall(r"-?\d+", n.get("bounds")))
        p = n
        while p in parent and p.get("checkable") != "true": p = parent[p]
        print((l + r) // 2, (t + b) // 2, p.get("checked"))
        break
'
}
# Opens the app on its settings and scrolls until the row is whole on screen; leaves "x y checked" in $row.
row=()
to_switch() {
  $ADB shell am force-stop $PKG; $ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell am start -n $PKG/.MainActivity > /dev/null; sleep 6
  $ADB shell input keyevent KEYCODE_WAKEUP; sleep 1; $ADB shell input keyevent KEYCODE_BACK; sleep 2      # the compass -> settings
  local size=$($ADB shell wm size | tr -d '\r' | sed 's/.*x//'); local mid=$(( size / 2 ))
  for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
    row=($(switch_row))
    if [ ${#row} -eq 3 ] && [ $row[2] -gt $(( size / 4 )) ] && [ $row[2] -lt $(( size * 3 / 4 )) ]; then return 0; fi
    $ADB shell input swipe $mid $(( size * 60 / 100 )) $mid $(( size * 42 / 100 )) 600; sleep 1
  done
  row=(); return 1
}
fresh_install() { $ADB uninstall $PKG > /dev/null 2>&1; $ADB install $APK | tail -1; sleep 4; $ADB shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION; }
add_waypoint() { $ADB shell am force-stop $PKG; sleep 1; $ADB shell "run-as $PKG sqlite3 databases/waypoints.db \"INSERT INTO waypoints(name,lat,lon,accuracyM,createdAt,hue,isTarget) VALUES('$NAME',43.7058,-72.2896,5.0,1760000000001,0,1);\""; }
# A switch key of the settings file as it is stored: true, false, or absent. The file is read whole and the value
# byte after the key is looked at (a preferences file stores a boolean as the bytes 08 00 or 08 01 after the key).
pref() {
  $ADB exec-out "run-as $PKG cat files/datastore/settings.preferences_pb 2>/dev/null" | python3 -c '
import sys
data = sys.stdin.buffer.read(); key = sys.argv[1].encode()
i = data.find(key + b"\x12")
if i < 0: print("absent"); sys.exit(0)
tail = data[i + len(key):i + len(key) + 6]
j = tail.find(b"\x08")
print("absent" if j < 0 or j + 1 >= len(tail) else ("true" if tail[j + 1] == 1 else "false"))
' "$1"
}
stored() { echo "$(pref backup_enabled)/$(pref backup_off_by_wearer)"; }
# The app's own folders, listed in one go; "LISTED" at the end shows the listing ran.
listing() { $ADB shell "run-as $PKG sh -c 'ls files no_backup databases 2>/dev/null; echo LISTED'" | tr -d '\r' | tr '\n' ' '; }
with_kind() { # with_kind none|encrypted|transfer
  case $1 in
    encrypted) $ADB shell settings put secure backup_local_transport_parameters is_encrypted=true ;;
    transfer) $ADB shell settings put secure backup_local_transport_parameters is_device_transfer=true ;;
    *) $ADB shell settings delete secure backup_local_transport_parameters > /dev/null ;;
  esac
  sleep 1
}
MARKER_ONLY="apps/$PKG/_manifest apps/$PKG/f/backup-switched-off"
marker_alone() { # marker_alone <what> : the last backup succeeded and holds the manifest and the marker and nothing else
  need "$1: the backup succeeded ($r)" '[ "$r" = "Success" ]'
  need "$1: the archive holds the manifest and the marker and nothing else" '[ "${${=entries}[*]}" = "$MARKER_ONLY" ]'
  need "$1: the waypoint's name is not in the archive" '[ "$names" = "0" ]'
}
turn_off() { # through the question; the row has to show on
  to_switch; need "the switch shows on before the question (${row[3]})" '[ "$row[3]" = "true" ]'
  $ADB shell input tap $row[1] $row[2]; sleep 2.5
  need "the question opened" 'scripts/ui-dump.sh 2>/dev/null | grep -q "Turn off backup?"'
  need "its answer was tapped" 'scripts/tap-text.sh "Turn off" > /dev/null 2>&1'; sleep 2
  need "the switch is stored off, turned off by the wearer ($(stored))" '[ "$(stored)" = "false/true" ]'
}
turn_on() { # one tap, no question; the row has to show off
  to_switch; need "the switch shows off before the tap (${row[3]})" '[ "$row[3]" = "false" ]'
  $ADB shell input tap $row[1] $row[2]; sleep 2
  need "a tap turned it on with no question" '! scripts/ui-dump.sh 2>/dev/null | grep -q "Turn off backup?"'
  need "the switch is stored on, with nothing recorded against it ($(stored))" '[ "$(stored)" = "true/false" ]'
}
home_and_end() { $ADB shell input keyevent KEYCODE_HOME; sleep 2; $ADB shell am kill $PKG; sleep 1; }

echo "== $($ADB shell getprop ro.boot.qemu.avd_name | tr -d '\r'), API $($ADB shell getprop ro.build.version.sdk | tr -d '\r'), $APK"
echo "as found: $was_enabled; transport $was_transport; transport parameters $was_params; screen lock disabled: $($ADB shell locksettings get-disabled 2>/dev/null | tr -d '\r')"
$ADB shell bmgr enable true > /dev/null; $ADB shell bmgr transport $LT > /dev/null; $ADB shell bmgr init $LT > /dev/null
with_kind none
fresh_install
$ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell am start -n $PKG/.MainActivity > /dev/null; sleep 6
add_waypoint; $ADB shell am start -n $PKG/.MainActivity > /dev/null; sleep 6; home_and_end

echo "1. switch on, not encrypted:"
r=$(backup); echo "  backup: $r"; read_transport
need "the pass did not succeed and the transport holds nothing" '[ "$r" != "Success" ] && [ -z "${entries// /}" ] && [ "$names" = "0" ]'

echo "2. switch on, encrypted:"
with_kind encrypted; r=$(backup); echo "  backup: $r"; read_transport
need "the backup succeeded" '[ "$r" = "Success" ]'
need "the archive holds the waypoint database" '[[ "$entries" == *"/db/waypoints.db"* ]]'
need "the waypoint's name is in the archive" '[ "$names" -ge 1 ]'

echo "2b. switch on, a transfer:"
with_kind transfer; r=$(backup); echo "  backup: $r"; read_transport
need "the transfer succeeded" '[ "$r" = "Success" ]'
need "the waypoint's name is in what was handed over" '[ "$names" -ge 1 ]'

echo "3. the switch turned off through the app's question; not encrypted:"
with_kind none; turn_off; home_and_end
r=$(backup); echo "  backup: $r"; read_transport; marker_alone "not encrypted"
left=$(listing)
need "the listing of the app's folders ran and no marker is left ($left)" '[[ "$left" == *LISTED* ]] && [[ "$left" != *backup-switched-off* ]]'

echo "3e. the same switch, encrypted:"
with_kind encrypted; r=$(backup); echo "  backup: $r"; read_transport; marker_alone "encrypted"

echo "3t. the same switch, a transfer:"
with_kind transfer; r=$(backup); echo "  backup: $r"; read_transport; marker_alone "a transfer"

echo "4t. the app uninstalled and installed again from that transfer:"
fresh_install
left=$(listing)
need "the listing ran; no database, marker or record ($left)" '[[ "$left" == *LISTED* ]] && [[ "$left" != *waypoints.db* ]] && [[ "$left" != *backup-switched-off* ]] && [[ "$left" != *backup-off-owed* ]]'
need "the switch is stored off, turned off by the wearer ($(stored))" '[ "$(stored)" = "false/true" ]'
to_switch; need "the switch shows off, with no question asked (${row[3]})" '[ "$row[3]" = "false" ]'; home_and_end

echo "4. a backup, not encrypted, and the app installed again from it:"
with_kind none; r=$(backup); echo "  backup: $r"; read_transport; marker_alone "not encrypted"
fresh_install
left=$(listing)
need "the listing ran; no database, marker or record ($left)" '[[ "$left" == *LISTED* ]] && [[ "$left" != *waypoints.db* ]] && [[ "$left" != *backup-switched-off* ]] && [[ "$left" != *backup-off-owed* ]]'
need "the switch is stored off, turned off by the wearer ($(stored))" '[ "$(stored)" = "false/true" ]'
to_switch; need "the switch shows off, with no question asked (${row[3]})" '[ "$row[3]" = "false" ]'
add_waypoint; $ADB shell am start -n $PKG/.MainActivity > /dev/null; sleep 6; home_and_end
with_kind encrypted; r=$(backup); echo "  the next backup, encrypted, with a waypoint in the app: $r"; read_transport; marker_alone "the next backup"

echo "5. the switch tapped on, an encrypted backup, and the app installed again:"
turn_on; home_and_end
with_kind encrypted; r=$(backup); echo "  backup: $r"; read_transport
need "the backup succeeded" '[ "$r" = "Success" ]'
need "the waypoint's name is in the archive" '[ "$names" -ge 1 ]'
fresh_install
back=$($ADB shell "run-as $PKG sqlite3 databases/waypoints.db 'select name from waypoints'" 2>&1 | tr -d '\r' | tr '\n' ' ')
need "the waypoint came back ($back)" '[[ "$back" == *$NAME* ]]'
need "the switch is stored on ($(stored))" '[ "$(stored)" = "true/false" ]'
to_switch; need "the switch shows on after that restore (${row[3]})" '[ "$row[3]" = "true" ]'; home_and_end

echo "6. the switch on; a record that a restore owes the switch off is put in the app's files; an encrypted backup, the app not started:"
# The app is not running: it was ended from Home. (A package that was stopped by force is not backed up.)
$ADB shell "run-as $PKG sh -c 'echo owed > files/backup-off-owed'"
need "the record is there" '[[ "$(listing)" == *backup-off-owed* ]]'
with_kind encrypted; r=$(backup); echo "  backup: $r"; read_transport; marker_alone "owed off"
need "the record is gone ($(listing))" '[[ "$(listing)" == *LISTED* ]] && [[ "$(listing)" != *backup-off-owed* ]]'
need "the switch is stored off, turned off by the wearer ($(stored))" '[ "$(stored)" = "false/true" ]'

echo "7. the switch on again; the settings folder takes no write; the marker's backup is restored over the app:"
with_kind none; turn_on; home_and_end
$ADB shell "run-as $PKG chmod 500 files/datastore"
mode=$($ADB shell "run-as $PKG ls -ld files/datastore" | tr -d '\r' | awk '{ print $1 }')
need "the settings folder takes no write ($mode)" '[ "$mode" = "dr-x------" ]'
token=$($ADB shell bmgr list sets 2>&1 | tr -d '\r' | grep -o '[0-9a-f]* *:' | head -1 | tr -d ' :')
echo "  bmgr restore $token: $($ADB shell bmgr restore $token $PKG 2>&1 | tr -d '\r' | tr '\n' ' ')"; sleep 6
left=$(listing)
need "the record of the marker's coming is there ($left)" '[[ "$left" == *backup-off-owed* ]]'
need "the stored switch still says on, since it could not be written ($(stored))" '[ "$(stored)" = "true/false" ]'
with_kind encrypted; r=$(backup); echo "  backup: $r"; read_transport; marker_alone "owed off, settings unwritable"
need "the record is still there ($(listing))" '[[ "$(listing)" == *backup-off-owed* ]]'
to_switch; need "the app shows the switch off while it is owed off (${row[3]})" '[ "$row[3]" = "false" ]'; home_and_end
$ADB shell am kill $PKG; $ADB shell "run-as $PKG chmod 700 files/datastore"
$ADB shell am start -n $PKG/.MainActivity > /dev/null; sleep 6; home_and_end
need "with the folder writable and the app started, the switch is stored off, turned off by the wearer ($(stored))" '[ "$(stored)" = "false/true" ]'
need "and the record is gone ($(listing))" '[[ "$(listing)" == *LISTED* ]] && [[ "$(listing)" != *backup-off-owed* ]]'

echo "8. the switch on; the app's files folder takes no write; an encrypted backup:"
with_kind none; turn_on; home_and_end
$ADB shell "run-as $PKG chmod 500 files"
mode=$($ADB shell "run-as $PKG ls -ld files" | tr -d '\r' | awk '{ print $1 }')
need "the files folder takes no write ($mode)" '[ "$mode" = "dr-x------" ]'
with_kind encrypted; r=$(backup); echo "  backup: $r"; read_transport
need "nothing was handed over" '[ "$r" != "Success" ] && [ "$names" = "0" ] && [[ "$entries" != *waypoints.db* ]]'
$ADB shell "run-as $PKG chmod 700 files"
r=$(backup); echo "  the same with the folder writable again: $r"; read_transport
need "the backup succeeded with the waypoint in it" '[ "$r" = "Success" ] && [ "$names" -ge 1 ]'
with_kind none

finish
if [ $bad -eq 0 ]; then echo "backup-off-check: every requirement held"; else echo "backup-off-check: FAILED"; fi
exit $bad
