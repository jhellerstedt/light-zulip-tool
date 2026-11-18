#!/usr/bin/env bash
#
# Build Zulip from this checkout and install it on a connected phone.
#
#   scripts/sideload.sh            debug build with the shared SDK dev key
#   scripts/sideload.sh release    release build; uses keystore.properties
#                                  when it names a keystore, otherwise the
#                                  shared dev key
#
# This compiles the tool against the SDK in this checkout, including any
# local SDK edits. Those edits are not part of a Light portal build. Run
# scripts/store-build-check.sh before submitting there.
#
# Installs signed with the same key replace the app and keep its data.
# Neither key matches an APK Light signed, so switching to or from a portal
# build means uninstalling first.

set -euo pipefail
cd "$(dirname "$0")/.."

VARIANT="${1:-debug}"
case "$VARIANT" in
    debug|release) ;;
    -h|--help)
        sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    *)
        echo "usage: scripts/sideload.sh [debug|release]" >&2
        exit 2
        ;;
esac

TASK="assemble$(tr '[:lower:]' '[:upper:]' <<< "${VARIANT:0:1}")${VARIANT:1}"
./gradlew ":tool:$TASK"

APK="$(find "tool/build/outputs/apk/$VARIANT" -name '*.apk' ! -name '*unsigned*' -print -quit)"
if [[ -z "$APK" ]]; then
    echo "no $VARIANT APK under tool/build/outputs/apk/$VARIANT" >&2
    exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
    echo "built $APK"
    echo "no device connected; install later with: adb install -r $APK" >&2
    exit 1
fi

adb install -r "$APK"
adb shell am start -n com.jhellerstedt.zulip/com.thelightphone.sdk.LightActivity
echo "installed $APK"
