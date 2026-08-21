#!/usr/bin/env bash
# Run Gradle and, if it fails, print the part of the log that says why.
#
# --stacktrace turns one useful line into six hundred lines of Gradle
# internals after it, so reading the tail of a CI log tells you only that
# something failed. Twice now that has cost a round trip: once for a Kotlin
# smart-cast error, once for an AAR metadata mismatch. This prints the
# diagnosis at the end, where it can be found.
set -o pipefail
LOG=$(mktemp)
if ./gradlew --no-daemon "$@" 2>&1 | tee "$LOG"; then
  rm -f "$LOG"
  exit 0
fi

echo "::group::What actually went wrong"
# Kotlin diagnostics, failed tasks, dependency and SDK complaints, and the
# assertion text from failing tests — everything except the stack traces.
grep -nE '^(e|w): |FAILURE: |^> Task .* FAILED|requires .* compile against|higher Android Gradle Plugin|Could not (find|resolve)|No matching variant|FAILED$|expected:|AssertionError|Caused by: ' "$LOG" \
  | grep -vE '^\s*[0-9]+:\s+at ' | head -60
echo "::endgroup::"

echo "::group::Tail, minus the stack traces"
grep -vE '^\s+at |^\s+\.\.\. [0-9]+ more' "$LOG" | tail -50
echo "::endgroup::"

rm -f "$LOG"
exit 1
