#!/usr/bin/env bash
#
# Build the Zulip tool the way the Light portal would, and report what stops it.
#
#   scripts/store-build-check.sh                 working tree on upstream/main
#   scripts/store-build-check.sh --offline       same, with Gradle --offline
#   scripts/store-build-check.sh --sdk-ref REF   another Light SDK commit
#   scripts/store-build-check.sh --head          the last commit, not the
#                                                working tree
#
# The portal copies tool/lighttool.toml, tool/build.gradle.kts, and
# tool/src/main into a clean SDK, then runs:
#   :tool:assembleRelease --no-build-cache
#     -DlightSdk.unsigned=true -DlightSdk.toolOnly=true
#     -DlightSdk.abiFilters=arm64-v8a
# SDK edits in this fork are not part of that build. This script repeats it
# with Light's extractor from the SDK commit being checked. The APK it
# produces is unsigned. Install day-to-day builds with scripts/sideload.sh.
#
# Exit status: 0 when the portal-shaped build succeeds, 1 when it does not,
# 2 on misuse. Output lands in build/store-check/.

set -Eeuo pipefail
cd "$(dirname "$0")/.."

ROOT="$(pwd)"
OUT="$ROOT/build/store-check"
SDK_REF="upstream/main"
OFFLINE=0
FROM_HEAD=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --offline) OFFLINE=1; shift ;;
        --head) FROM_HEAD=1; shift ;;
        --sdk-ref) SDK_REF="${2:?--sdk-ref needs a commit}"; shift 2 ;;
        -h|--help) sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown flag: $1" >&2; exit 2 ;;
    esac
done

if ! git rev-parse --verify --quiet "$SDK_REF^{commit}" >/dev/null; then
    echo "cannot resolve $SDK_REF. Fetch it first, for example: git fetch upstream" >&2
    exit 2
fi

WORKSPACE="$OUT/workspace"
DEV_REPO="$OUT/dev-repo"
REPORT="$OUT/report"
LOG="$OUT/build.log"
rm -rf "$WORKSPACE" "$DEV_REPO" "$REPORT"
mkdir -p "$WORKSPACE" "$DEV_REPO" "$REPORT"
: > "$LOG"

SDK_SHA="$(git rev-parse --short "$SDK_REF^{commit}")"
echo ">> pristine SDK @ $SDK_SHA ($SDK_REF)"
git archive "$SDK_REF" | tar -x -C "$WORKSPACE"

if [[ $FROM_HEAD -eq 1 ]]; then
    echo ">> tool from the last commit ($(git rev-parse --short HEAD))"
    git archive HEAD -- tool | tar -x -C "$DEV_REPO"
else
    echo ">> tool from the working tree"
    git ls-files -z --cached --others --exclude-standard -- tool |
        while IFS= read -r -d '' f; do
            [[ -f "$f" || -L "$f" ]] || continue
            mkdir -p "$DEV_REPO/$(dirname "$f")"
            cp -P "$f" "$DEV_REPO/$f"
        done
fi

echo ">> staging with Light's extractor"
if ! PYTHONPATH="$WORKSPACE/builder" python3 -m lightbuilder prepare \
        --dev-repo "$DEV_REPO" \
        --workspace-tool "$WORKSPACE/tool" \
        --tool-path tool \
        --output-dir "$REPORT" >>"$LOG" 2>&1; then
    echo "!! the extractor refused the tool:" >&2
    cat "$REPORT/error.json" 2>/dev/null >&2 || tail -20 "$LOG" >&2
    exit 1
fi

[[ -f local.properties ]] && cp local.properties "$WORKSPACE/"

GRADLE_ARGS=(
    ":tool:assembleRelease"
    "--no-build-cache"
    "--console=plain"
    "--continue"
    "-DlightSdk.unsigned=true"
    "-DlightSdk.toolOnly=true"
    "-DlightSdk.abiFilters=arm64-v8a"
)
if [[ $OFFLINE -eq 1 ]]; then
    GRADLE_ARGS+=("--offline")
fi

echo ">> ./gradlew ${GRADLE_ARGS[*]}"
set +e
(cd "$WORKSPACE" && ./gradlew "${GRADLE_ARGS[@]}") >>"$LOG" 2>&1
STATUS=$?
set -e

if [[ $STATUS -eq 0 ]] && grep -q "^BUILD SUCCESSFUL" "$LOG"; then
    APK="$(find "$WORKSPACE/tool/build/outputs/apk/release" -name '*.apk' -print -quit)"
    echo "== store build: OK — unsigned APK ${APK#"$ROOT"/}"
    grep -E '^version(Code|Name) ' "$ROOT/tool/lighttool.toml" | sed 's/^/   /'
    exit 0
fi

ERRORS="$(grep -cE '^e: ' "$LOG" || true)"
echo "== store build: FAILED — $ERRORS compiler errors (full log: ${LOG#"$ROOT"/})"
if [[ "$ERRORS" -gt 0 ]]; then
    echo
    echo "by file:"
    grep -E '^e: ' "$LOG" | grep -v '^e: \[ksp\]' |
        sed -E 's#^e: file://[^ ]*/src/main/(kotlin|java)/##; s#:[0-9]+:[0-9]+ .*##' |
        sort | uniq -c | sort -rn | sed 's/^/  /'
    echo
    echo "by missing symbol:"
    grep -E '^e: ' "$LOG" | grep -oE "Unresolved reference '[^']+'" |
        sed -E "s/Unresolved reference '([^']+)'/\1/" |
        sort | uniq -c | sort -rn | sed 's/^/  /' || true
fi
grep -E "Could not resolve|No cached version|Light SDK violation|not allowed|What went wrong" -A2 "$LOG" |
    grep -vE '^--$' | sort -u | head -20 | sed 's/^/  /' || true
exit 1
