#!/usr/bin/env bash
#
# build_branches.sh — multi-app branch build/version automation.
#
# A generalisation of the single-app script from dividetask/dungeon-boss: it
# builds the branches of SEVERAL Android apps in one loop. For every app, for
# every changed branch, it checks the branch out, builds the app, saves the
# build output to a log, copies the new APK to versions/latest.apk, and:
#   - if latest.apk changed   -> copies it to the next version file (v0.017.apk)
#                                and commits the APKs + log.
#   - if latest.apk unchanged -> commits just the build output (the error message).
# then pushes, so the built APKs live on each branch.
#
# Branches are discovered automatically per app (git fetch + for-each-ref on
# $GLOB, default claude/*); you never pass branch names. New branches and
# branches whose tip commit changed since the last build are built; unchanged
# ones are skipped (per-app state in each repo's .git/build-automation-state).
#
# Runs forever: after each full pass over all apps it waits 15 minutes, then
# starts again. Stop it with Ctrl-C.
#
# WHERE TO RUN IT FROM
#   The apps are expected to be checked out as sibling directories under $BASE.
#   $BASE defaults to the directory THIS script lives in, so a normal layout is:
#       Projects/
#       ├── build_branches.sh    (this script, sitting loose here)
#       ├── language-games/
#       ├── dungeon-boss/
#       └── homeschool-teacher/
#   Keep the script OUTSIDE the app repos (as above) — the script checks out
#   branches in those working trees, so if it lived inside one of them a branch
#   checkout could swap the running script file out from under you. Override the
#   location with BASE=/path/to/checkouts.
#
# Usage: build_branches.sh    (no arguments; always commits and pushes)
#
set -uo pipefail

# --- Apps to build -----------------------------------------------------------
# One entry per app:  "<dir>|<gradle-subdir>|<git-url>"
#   <dir>            local checkout directory name under $BASE
#   <gradle-subdir>  where the Gradle project (gradlew, app/) lives inside the
#                    repo — "android" if nested, "." if it is the repo root
#   <git-url>        remote to clone from if <dir> is missing (AUTOCLONE)
APPS=(
  "language-games|android|https://github.com/dividetask/language-games"
  "dungeon-boss|android|https://github.com/dividetask/dungeon-boss"
  "homeschool-teacher|.|https://github.com/dividetask/homeschool-teacher"
)

# --- Configuration (override via environment) --------------------------------
BASE="${BASE:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"  # holds the app checkouts
REMOTE="${REMOTE:-origin}"
GLOB="${GLOB:-claude/*}"          # which branches to build
TASK="${TASK:-:app:assembleDebug}"
WAIT="${WAIT:-900}"               # seconds to sleep between full passes
AUTOCLONE="${AUTOCLONE:-1}"       # clone a missing app dir from its <git-url>

[ "$#" -gt 0 ] && { echo "usage: build_branches.sh  (no arguments)" >&2; exit 2; }

# Make sure Gradle can find the Android SDK. It reads ANDROID_HOME /
# ANDROID_SDK_ROOT from the environment, or sdk.dir from <projdir>/local.properties.
# When this script runs detached (cron / the forever-loop) those env vars are
# often unset, so detect a local SDK and write local.properties — it is
# gitignored and machine-specific, so it is never committed. Returns non-zero
# (and leaves the build to fail with Gradle's own message) if no SDK is found.
# $1 = the Gradle project directory (where local.properties belongs).
ensure_sdk() {
  local projdir="$1" c sdk=""
  for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
           "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" \
           "/usr/lib/android-sdk" "/opt/android-sdk" "/opt/android/sdk"; do
    [ -n "$c" ] || continue
    if [ -d "$c/platform-tools" ] || [ -d "$c/platforms" ] || [ -d "$c/cmdline-tools" ]; then
      sdk="$c"; break
    fi
  done
  if [ -z "$sdk" ]; then
    echo "    warning: no Android SDK found (set ANDROID_HOME or create $projdir/local.properties)"
    return 1
  fi
  export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
  if ! grep -qs '^sdk\.dir=' "$projdir/local.properties" 2>/dev/null; then
    echo "sdk.dir=$sdk" > "$projdir/local.properties"
    echo "    wrote $projdir/local.properties (sdk.dir=$sdk)"
  fi
  return 0
}

# Next version file from existing vMAJOR.MINOR.apk files in $VERSIONS. Finds the
# highest existing version (compared by major, then minor) and bumps the minor
# while keeping that major (v0.016.apk -> v0.017.apk; v1.001.apk -> v1.002.apk).
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

# Build every changed branch of one app.
# $1 = app label, $2 = repo dir (absolute), $3 = gradle subdir ("." or "android")
process_app() {
  local app="$1" REPO="$2" proj="$3"

  cd "$REPO" || { echo "  cannot cd into $REPO — skipping $app"; return; }
  git rev-parse --git-dir >/dev/null 2>&1 || { echo "  $REPO is not a git repo — skipping $app"; return; }

  # Paths are relative to the repo root. With the Gradle project at the repo
  # root (proj="."), there is no "android/" prefix; when nested, everything sits
  # under that subdir.
  local pre=""; [ "$proj" = "." ] || pre="$proj/"
  local APK="${pre}app/build/outputs/apk/debug/app-debug.apk"
  VERSIONS="${pre}versions"        # global so next_version() can read it
  local LOGS="${pre}build-logs"
  local STATE="$REPO/.git/build-automation-state"

  git fetch --prune "$REMOTE" >/dev/null 2>&1 || echo "  warning: fetch failed for $app"

  # Discover branches automatically — always looked up, never passed in.
  local BRANCHES=() r
  while IFS= read -r r; do BRANCHES+=("${r#"$REMOTE"/}"); done \
    < <(git for-each-ref --format='%(refname:short)' "refs/remotes/$REMOTE/$GLOB" | grep -v '/HEAD$')
  [ "${#BRANCHES[@]}" -gt 0 ] || echo "  no branches match $REMOTE/$GLOB"
  echo "  branches: ${BRANCHES[*]:-(none)}"

  local branch sha last log rc sig name v committed tip
  for branch in ${BRANCHES[@]+"${BRANCHES[@]}"}; do
    # Safety guard: only ever build claude/ branches. Branch discovery already
    # restricts to $GLOB (default claude/*), but GLOB is an overridable env var —
    # this hard prefix check guarantees main (or any non-claude/ branch) is never
    # checked out, built, committed to, or pushed, no matter what GLOB is set to.
    case "$branch" in
      claude/*) ;;
      *) echo "    skip $branch (not a claude/ branch)"; continue ;;
    esac

    sha="$(git rev-parse --verify --quiet "$REMOTE/$branch")" || continue
    last="$(grep "^$branch " "$STATE" 2>/dev/null | awk '{print $2}' | tail -1)"
    [ "$sha" = "$last" ] && { echo "  skip $branch (no changes)"; continue; }

    echo "  === $app / $branch (${sha:0:7}) ==="
    git checkout -B "$branch" "$REMOTE/$branch" >/dev/null 2>&1 || { echo "    checkout failed"; continue; }
    mkdir -p "$LOGS" "$VERSIONS"
    # One log per branch is unnecessary: the file is committed to the branch it
    # describes, so each branch only ever carries its own. Use a fixed name.
    log="$LOGS/build.log"

    if [ -f "${pre}gradlew" ]; then
      ensure_sdk "$proj" || true
      echo "    building: (cd $proj && ./gradlew $TASK)"
      ( cd "$proj" && ./gradlew "$TASK" ) > "$log" 2>&1
      rc=$?
      echo "    gradle exit $rc"
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
      echo "no Gradle project at '$proj' on this branch" > "$log"
    fi

    git add "$LOGS"
    committed=0
    if [ -n "$(git status --porcelain -- "$VERSIONS/latest.apk")" ]; then
      v="$(next_version)"
      cp "$VERSIONS/latest.apk" "$VERSIONS/$v"
      git add "$VERSIONS/latest.apk" "$VERSIONS/$v"
      git commit -q -m "Build $app/$branch: $v (from ${sha:0:7})" && { committed=1; echo "    committed $v"; }
    elif ! git diff --cached --quiet; then
      git commit -q -m "Build $app/$branch: no APK change (from ${sha:0:7})" && { committed=1; echo "    committed build output"; }
    fi

    # Push the new commit so the version files / build output are saved on the remote.
    [ "$committed" -eq 1 ] && { git push -u "$REMOTE" "$branch" || echo "    push failed"; }

    # Record the branch tip as it now stands on the remote (a successful push
    # updates origin/<branch> too). Next run's fetch sees this same SHA and skips
    # — so our own version-bump commit doesn't re-trigger a build. Only real new
    # work that moves the tip will differ and rebuild.
    tip="$(git rev-parse "$REMOTE/$branch")"
    grep -v "^$branch " "$STATE" 2>/dev/null > "$STATE.tmp" || true
    echo "$branch $tip" >> "$STATE.tmp"; mv "$STATE.tmp" "$STATE"
  done
}

# Clone an app's repo into $BASE if it is not there yet (best effort).
ensure_checkout() {
  local dir="$1" url="$2"
  [ -d "$dir/.git" ] && return 0
  [ "$AUTOCLONE" = "1" ] && [ -n "$url" ] || {
    echo "  missing checkout: $dir (set AUTOCLONE=1 with a URL, or clone it yourself)"
    return 1
  }
  echo "  cloning $url -> $dir"
  git clone "$url" "$dir" || { echo "  clone failed: $url"; return 1; }
}

# --- Main loop: one full pass over all apps, then wait, forever. -------------
while true; do
  for entry in "${APPS[@]}"; do
    IFS='|' read -r dir subdir url <<< "$entry"
    REPO="$BASE/$dir"
    echo "### app: $dir  (gradle dir: $subdir)"
    ensure_checkout "$REPO" "$url" || continue
    process_app "$dir" "$REPO" "$subdir"
  done
  echo "pass complete; waiting $((WAIT / 60)) minutes before next run..."
  sleep "$WAIT"
done
