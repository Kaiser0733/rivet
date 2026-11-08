#!/usr/bin/env bash
# A2 closing fix C1: no provider-screen Text call prints a stored string unescaped.
#
# The first attempt at this check was recorded as a receipt whose command the ledger
# re-runs as a plain string, so `bash -c '! grep ...'` was stored as `bash -c ! grep ...`
# and can never pass. The rule lives here instead, where the recorded command is just
# `bash round3/provider-text-check.sh`.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2

files=(
  app/src/main/java/com/kaiser/rivet/ui/provider/ProviderEditor.kt
  app/src/main/java/com/kaiser/rivet/ui/provider/SettingsScreen.kt
)
# Variables these screens print straight from state. Each one must be wrapped in
# displaySafeText; a bare `Text(<var>, ...)` render is the failure this looks for.
bare='(^|[^A-Za-z_])Text\((test\.message|err|message|it|error|fetchError|loadError|autonomyError|appearanceError|headerNotice)[,)]'
wrapped='Text\(displaySafeText\((test\.message|err|message|it|error)\)'

status=0
if grep -nE "$bare" "${files[@]}"; then
  echo "FAIL a stored string is printed without displaySafeText (above)"
  status=1
else
  echo "PASS no provider-screen Text call prints a stored string unescaped"
fi

# Six rows: two in the editor, four in Settings (two of them bind the name `message`).
count=$(grep -hoE "$wrapped" "${files[@]}" | wc -l)
if [ "$count" -eq 6 ]; then
  echo "PASS all six stored-string rows route through displaySafeText"
else
  echo "FAIL expected 6 escaped rows, found $count"
  status=1
fi

exit "$status"
