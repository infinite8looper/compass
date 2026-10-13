#!/bin/zsh
# Usage: scripts/backup-off-check.sh
# Checks, on the attached emulator and with the debug app as it is built (app/build/outputs/apk/debug/app-debug.apk),
# what the app's backup agent hands the system's backup in three cases, by running the system's own backup
# (`bmgr backupnow`) against the system's local transport and reading what the transport then holds:
#   1. `Back up waypoints` on, the transport not saying it encrypts (an emulator has no screen lock): what is handed
#      over is printed and not judged; it is what the backup rules' encryption requirement leaves.
#   2. `Back up waypoints` on, the local transport told to say it encrypts (its own test parameter,
#      `settings put secure backup_local_transport_parameters is_encrypted=true`): the backup has to succeed
#      and the stored archive has to hold the waypoint database with the waypoint's name in it.
#   2b. the same with the transport told to say the pass is a transfer to another device (`is_device_transfer=true`):
#      the transfer has to succeed with the waypoint's name in what was handed over.
#   3b. after 3, a pass that says it is a transfer: nothing may be handed over.
#   3. `Back up waypoints` turned off in the app through its question (`Turn off`), the transport not saying it
#      encrypts: the backup has to succeed, the stored archive has to hold the manifest and the marker
#      `backup-switched-off` and nothing else, the waypoint's name must not be in it, and no marker may be left in
#      the app's files.
# Exit status 0 if every requirement of 2, 2b, 3 and 3b holds, 1 otherwise.
# It wipes the app's data (it installs the app afresh), puts one waypoint in its database with the device's sqlite3,
# switches the Backup Manager on with the local transport and empties that transport, and reads the transport's
# files as root. All of that is put back when it ends, also when it is stopped: the transport and the Backup Manager
# as they were, the transport's parameter removed, adbd not rooted, the app installed with its data cleared.
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
  $ADB shell pm clear $PKG > /dev/null 2>&1
  echo "put back: $($ADB shell bmgr enabled | tr -d '\r'); transport $($ADB shell bmgr list transports | tr -d '\r' | sed -n 's/^ *\* //p'); transport parameters $($ADB shell settings get secure backup_local_transport_parameters | tr -d '\r'); adbd uid $($ADB shell id -u | tr -d '\r')"
}
trap 'put_back; exit 1' INT TERM

bad=0
need() { if eval "$2"; then echo "  ok: $1"; else echo "  NOT SO: $1"; bad=1; fi; }
backup() { $ADB shell bmgr backupnow $PKG 2>&1 | tr -d '\r' | sed -n "s/.*Package $PKG with result: //p" | tail -1; }
archive=""; entries=""; names=0
read_transport() {
  $ADB root > /dev/null; sleep 3
  archive=$($ADB shell "find /data/data/com.android.localtransport -type f 2>/dev/null | grep $PKG" | tr -d '\r' | tr '\n' ' ')
  if [ -n "${archive// /}" ]; then
    entries=$($ADB shell "cat $archive | grep -a -o 'apps/$PKG/[A-Za-z0-9_./-]*' | sort -u | tr '\n' ' '" | tr -d '\r')
    names=$($ADB shell "cat $archive | grep -c -a $NAME" | tr -d '\r')
  else entries=""; names=0; fi
  $ADB unroot > /dev/null; sleep 3
  echo "  the transport holds: ${entries:-nothing}; '$NAME' in it: $names time(s)"
}
home_and_end() { $ADB shell input keyevent KEYCODE_HOME; sleep 2; $ADB shell am kill $PKG; sleep 1; }

echo "== $($ADB shell getprop ro.boot.qemu.avd_name | tr -d '\r'), API $($ADB shell getprop ro.build.version.sdk | tr -d '\r'), $APK"
echo "as found: $was_enabled; transport $was_transport; transport parameters $was_params; screen lock disabled: $($ADB shell locksettings get-disabled 2>/dev/null | tr -d '\r')"
$ADB shell bmgr enable true > /dev/null; $ADB shell bmgr transport $LT > /dev/null; $ADB shell bmgr init $LT > /dev/null
$ADB shell settings delete secure backup_local_transport_parameters > /dev/null
$ADB uninstall $PKG > /dev/null 2>&1; $ADB install $APK | tail -1
$ADB shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
$ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell am start -n $PKG/.MainActivity > /dev/null; sleep 6; $ADB shell am force-stop $PKG; sleep 1
$ADB shell "run-as $PKG sqlite3 databases/waypoints.db \"INSERT INTO waypoints(name,lat,lon,accuracyM,createdAt,hue,isTarget) VALUES('$NAME',43.7058,-72.2896,5.0,1760000000001,0,1);\""
$ADB shell am start -n $PKG/.MainActivity > /dev/null; sleep 6; home_and_end

echo "1. switch on, the transport does not say it encrypts:"
echo "  backup: $(backup)"; read_transport

echo "2. switch on, the transport says it encrypts:"
$ADB shell settings put secure backup_local_transport_parameters is_encrypted=true; sleep 1
r=$(backup); echo "  backup: $r"; read_transport
need "the backup succeeded" '[ "$r" = "Success" ]'
need "the archive holds the waypoint database" '[[ "$entries" == *"/db/waypoints.db"* ]]'
need "the waypoint's name is in the archive" '[ "$names" -ge 1 ]'
$ADB shell settings delete secure backup_local_transport_parameters > /dev/null; sleep 1

echo "2b. switch on, the transport says the pass is a transfer to another device:"
$ADB shell settings put secure backup_local_transport_parameters is_device_transfer=true; sleep 1
r=$(backup); echo "  backup: $r"; read_transport
need "the transfer succeeded" '[ "$r" = "Success" ]'
need "the waypoint's name is in what was handed over" '[ "$names" -ge 1 ]'
$ADB shell settings delete secure backup_local_transport_parameters > /dev/null; sleep 1

echo "3. the switch turned off through the app's question, the transport does not say it encrypts:"
$ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell am start -n $PKG/.MainActivity > /dev/null; sleep 6
$ADB shell input keyevent KEYCODE_WAKEUP; sleep 1; $ADB shell input keyevent KEYCODE_BACK; sleep 2      # the compass -> settings
size=$($ADB shell wm size | tr -d '\r' | sed 's/.*x//'); mid=$(( size / 2 ))
tapped=1
for i in 1 2 3 4 5 6 7 8 9 10; do
  spot=($(scripts/tap-text.sh "Back up waypoints" 2>/dev/null | sed -n "s/.* at \([0-9]*\) \([0-9]*\)$/\1 \2/p"))
  # Tapped where the row is whole: between a quarter and three quarters of the way down. Otherwise scrolled on.
  if [ ${#spot} -eq 2 ] && [ $spot[2] -gt $(( size / 4 )) ] && [ $spot[2] -lt $(( size * 3 / 4 )) ]; then tapped=0; break; fi
  [ ${#spot} -eq 2 ] && { sleep 1.5; $ADB shell input keyevent KEYCODE_BACK; sleep 1.5; }    # tapped at the edge: leave the question if it opened
  $ADB shell input swipe $mid $(( size * 60 / 100 )) $mid $(( size * 42 / 100 )) 600; sleep 1
done
sleep 2
need "the question opened" 'scripts/ui-dump.sh 2>/dev/null | grep -q "Turn off backup?"'
scripts/tap-text.sh "Turn off" > /dev/null 2>&1; sleep 2
settings_file=$($ADB shell "run-as $PKG cat files/datastore/settings.preferences_pb" | strings | tr '\n' ' ')
need "the settings file records that the wearer turned it off" '[[ "$settings_file" == *backup_off_by_wearer* ]]'
home_and_end
r=$(backup); echo "  backup: $r"; read_transport
need "the backup succeeded" '[ "$r" = "Success" ]'
need "the archive holds the manifest and the marker and nothing else" '[ "${${=entries}[*]}" = "apps/$PKG/_manifest apps/$PKG/f/backup-switched-off" ]'
need "the waypoint's name is not in the archive" '[ "$names" = "0" ]'
left=$($ADB shell "run-as $PKG ls files" | tr -d '\r' | tr '\n' ' ')
need "no marker is left in the app's files ($left)" '[[ "$left" != *backup-switched-off* ]]'

echo "3b. the switch off by the wearer, the transport says the pass is a transfer to another device:"
$ADB shell settings put secure backup_local_transport_parameters is_device_transfer=true; sleep 1
r=$(backup); echo "  backup: $r"; read_transport
need "nothing was handed over" '[ "$r" != "Success" ] && [ "$names" = "0" ] && [[ "$entries" != *backup-switched-off* ]]'
$ADB shell settings delete secure backup_local_transport_parameters > /dev/null

put_back
if [ $bad -eq 0 ]; then echo "backup-off-check: every requirement held"; else echo "backup-off-check: FAILED"; fi
exit $bad
