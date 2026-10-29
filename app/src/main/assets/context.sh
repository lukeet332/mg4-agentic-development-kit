#!/system/bin/sh
# ADK context timeline: every $2 seconds, each section of the car's state that changed since the last pass.
# Override without a rebuild by writing files/context.sh in ADK's private directory.
out="$1"; interval="${2:-30}"; tmp="$(dirname "$out")/.ctx-$$"
mkdir -p "$tmp"
section() {
  name="$1"; shift
  sh -c "$*" > "$tmp/$name.new" 2>&1
  if ! cmp -s "$tmp/$name.new" "$tmp/$name.old"; then
    { echo "### $(date '+%m-%d %H:%M:%S') $name"; cat "$tmp/$name.new"; } >> "$out"
    mv "$tmp/$name.new" "$tmp/$name.old"
  fi
}
while true; do
  section foreground "dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity' | head -2; dumpsys window | grep -m1 mCurrentFocus"
  # Not ps -A: every root process it cannot stat logs an SELinux denial, flooding the kernel log we mirror.
  section processes "dumpsys activity processes | grep -oE 'ProcessRecord\\{[0-9a-f]+ [0-9]+:[^ /}]+' | sed -E 's/ProcessRecord\\{[0-9a-f]+ [0-9]+://' | sort -u"
  section services "dumpsys activity services | grep '^  \* ServiceRecord' | sed -E 's/ServiceRecord\{[0-9a-f]+ u[0-9]+ //' | sort -u"
  section audio_focus "dumpsys audio | sed -n '/Audio Focus stack entries/,/Audio Mix Focus/p' | sed -E 's/@[0-9a-f]+//g'"
  section media_sessions "dumpsys media_session | grep -E 'package=|state=PlaybackState|description=' | sed -E 's/(position|updated|buffered position)=[0-9-]+//g'"
  section bluetooth "dumpsys bluetooth_manager | grep -E 'Profile:|StateMachine: name=|mCurrentDevice'"
  section usb "for d in /sys/bus/usb/devices/*; do [ -f \$d/idVendor ] && echo \$(basename \$d) \$(cat \$d/idVendor):\$(cat \$d/idProduct) \$(cat \$d/product 2>/dev/null); done"
  section power "dumpsys power | grep -E 'mWakefulness=|Display Power'"
  section network "ip -4 -o addr | awk '{print \$2, \$4}'"
  sleep "$interval"
done
