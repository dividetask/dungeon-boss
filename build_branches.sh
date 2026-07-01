#!/usr/bin/env bash
#
# build_branches.sh — branch build/version automation for the Dungeon Boss Android app.
# Run this from a copy OUTSIDE the repo. Point it at the repo with REPO or by
# running it from inside the working tree.
#
# For every changed branch it checks the branch out, builds the Android app,
# saves the output to a log, copies the new APK to versions/latest.apk, and:
#   - if latest.apk changed   -> copies it to the next version file (v0.017.apk)
#                                and commits the APKs + log.
#   - if latest.apk unchanged -> commits just the build output (the error message).
#
# It discovers branches itself (git fetch + for-each-ref on $GLOB, default
# claude/*); you never pass branch names. New branches and branches whose tip
# commit changed since the last build are built; unchanged ones are skipped.
#
# Runs forever: after each full pass over the branches it waits 15 minutes,
# then starts again. Stop it with Ctrl-C.
#
# Usage: build_branches.sh   (no arguments; always commits and pushes)
#
set -uo pipefail

# Run this from ~/Projects/ ; the repo lives in the dungeon-boss subfolder.
# Override with REPO=/some/other/path if needed.
REPO="${REPO:-$PWD/dungeon-boss}"
REMOTE="${REMOTE:-origin}"
GLOB="${GLOB:-claude/*}"
TASK="${TASK:-:app:assembleDebug}"
APK="android/app/build/outputs/apk/debug/app-debug.apk"
VERSIONS="android/versions"
LOGS="android/build-logs"
STATE="$REPO/.git/build-automation-state"

[ "$#" -gt 0 ] && { echo "usage: build_branches.sh  (no arguments)" >&2; exit 2; }

cd "$REPO" || exit 1
git rev-parse --git-dir >/dev/null 2>&1 || { echo "not a git repo: $REPO" >&2; exit 1; }

# Make sure Gradle can find the Android SDK. It reads ANDROID_HOME /
# ANDROID_SDK_ROOT from the environment, or sdk.dir from android/local.properties.
# When this script runs detached (cron / the forever-loop) those env vars are
# often unset, so detect a local SDK and write local.properties — it is
# gitignored and machine-specific, so it is never committed. Returns non-zero
# (and leaves the build to fail with Gradle's own message) if no SDK is found.
ensure_sdk() {
  local c sdk=""
  for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
           "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" \
           "/usr/lib/android-sdk" "/opt/android-sdk" "/opt/android/sdk"; do
    [ -n "$c" ] || continue
    if [ -d "$c/platform-tools" ] || [ -d "$c/platforms" ] || [ -d "$c/cmdline-tools" ]; then
      sdk="$c"; break
    fi
  done
  if [ -z "$sdk" ]; then
    echo "  warning: no Android SDK found (set ANDROID_HOME or create android/local.properties)"
    return 1
  fi
  export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
  if ! grep -qs '^sdk\.dir=' android/local.properties 2>/dev/null; then
    echo "sdk.dir=$sdk" > android/local.properties
    echo "  wrote android/local.properties (sdk.dir=$sdk)"
  fi
  return 0
}

# Next version file from existing vMAJOR.MINOR.apk files. Finds the highest
# existing version (compared by major, then minor) and bumps the minor while
# keeping that major (v0.016.apk -> v0.017.apk; v1.001.apk -> v1.002.apk).
next_version() {
  local maxmajor=0 maxminor=0 major minor f; shopt -s nullglob
  for f in "$VERSIONS"/v*.apk; do
    [[ "$(basename "$f")" =~ ^v0*([0-9]+)\.0*([0-9]+)\.apk$ ]] || continue
    major="${BASH_REMATCH[1]}"; minor="${BASH_REMATCH[2]}"
    if (( major > maxmajor || (major == maxmajor && minor > maxminor) )); then
      maxmajor=$major; maxminor=$minor
    fi
  done
  shopt -u nullglob
  printf 'v%d.%03d.apk' "$maxmajor" $((maxminor + 1))
}

# Loop forever: do one full pass over all branches, then wait 15 minutes and repeat.
while true; do

git fetch --prune "$REMOTE" >/dev/null 2>&1 || echo "warning: fetch failed"

# Discover branches automatically — always looked up, never passed in.
BRANCHES=()
while IFS= read -r r; do BRANCHES+=("${r#"$REMOTE"/}"); done \
  < <(git for-each-ref --format='%(refname:short)' "refs/remotes/$REMOTE/$GLOB" | grep -v '/HEAD$')
[ "${#BRANCHES[@]}" -gt 0 ] || echo "no branches match $REMOTE/$GLOB"
echo "found branches: ${BRANCHES[*]:-(none)}"

for branch in ${BRANCHES[@]+"${BRANCHES[@]}"}; do
  sha="$(git rev-parse --verify --quiet "$REMOTE/$branch")" || continue
  last="$(grep "^$branch " "$STATE" 2>/dev/null | awk '{print $2}' | tail -1)"
  [ "$sha" = "$last" ] && { echo "skip $branch (no changes)"; continue; }

  echo "=== $branch (${sha:0:7}) ==="
  git checkout -B "$branch" "$REMOTE/$branch" >/dev/null 2>&1 || { echo "  checkout failed"; continue; }
  mkdir -p "$LOGS" "$VERSIONS"
  # One log per branch is unnecessary: the file is committed to the branch it
  # describes, so each branch only ever carries its own. Use a fixed name.
  log="$LOGS/build.log"

  if [ -d android ]; then
    ensure_sdk || true
    echo "  building: (cd android && ./gradlew $TASK)"
    ( cd android && ./gradlew "$TASK" ) > "$log" 2>&1
    rc=$?
    echo "  gradle exit $rc"
    if [ "$rc" -eq 0 ] && [ -f "$APK" ]; then
      cp "$APK" "$VERSIONS/latest.apk"
    elif [ "$rc" -eq 0 ]; then
      echo "BUILD reported success (exit 0) but no APK was found at $APK — nothing to version." >> "$log"
    elif [ "$rc" -gt 128 ]; then
      # Exit > 128 means the process was killed by a signal (rc = 128 + signal).
      # Gradle never produced a FAILURE block because it was terminated, not a
      # build error — say so instead of the misleading generic message.
      sig=$((rc - 128)); name="SIG$(kill -l "$sig" 2>/dev/null || echo "$sig")"
      echo "BUILD INTERRUPTED — Gradle was killed by signal $sig ($name), exit $rc." >> "$log"
      echo "This is not a compile error: the process was terminated before it could finish" >> "$log"
      echo "(e.g. Ctrl-C/interrupt for SIGINT, a timeout/kill for SIGTERM, or the OS" >> "$log"
      echo "out-of-memory killer for SIGKILL). No new APK; re-run the build to retry." >> "$log"
    else
      echo "BUILD FAILED (exit $rc) — no new APK. See the Gradle output above;" >> "$log"
      echo "re-run with --stacktrace or --info (append to TASK) for more detail." >> "$log"
    fi
  else
    echo "no android/ on this branch" > "$log"
  fi

  git add "$LOGS"
  committed=0
  if [ -n "$(git status --porcelain -- "$VERSIONS/latest.apk")" ]; then
    v="$(next_version)"
    cp "$VERSIONS/latest.apk" "$VERSIONS/$v"
    git add "$VERSIONS/latest.apk" "$VERSIONS/$v"
    git commit -q -m "Build $branch: $v (from ${sha:0:7})" && { committed=1; echo "  committed $v"; }
  elif ! git diff --cached --quiet; then
    git commit -q -m "Build $branch: no APK change (from ${sha:0:7})" && { committed=1; echo "  committed build output"; }
  fi

  # Push the new commit so the version files / build output are saved on the remote.
  [ "$committed" -eq 1 ] && { git push -u "$REMOTE" "$branch" || echo "  push failed"; }

  # Record the branch tip as it now stands on the remote (a successful push
  # updates origin/<branch> too). Next run's fetch sees this same SHA and skips
  # — so our own version-bump commit doesn't re-trigger a build. Only real new
  # work that moves the tip will differ and rebuild.
  tip="$(git rev-parse "$REMOTE/$branch")"
  grep -v "^$branch " "$STATE" 2>/dev/null > "$STATE.tmp" || true
  echo "$branch $tip" >> "$STATE.tmp"; mv "$STATE.tmp" "$STATE"
done

# Pass finished — wait 15 minutes (900s), then start the next pass.
echo "pass complete; waiting 15 minutes before next run..."
sleep 900
done
