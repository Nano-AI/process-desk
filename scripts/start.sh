#!/usr/bin/env bash
#
# Starts the local model server and the backend, in that order.
#
# Both have a failure that reads as something else, and this script exists to make each one
# say what it actually is:
#
#   - Ollama's model directory is an environment variable, and when it points at an external
#     disk that is not mounted, `ollama serve` dies with "mkdir: permission denied" — which
#     reads as a permissions problem rather than a missing disk.
#   - Gradle's toolchain needs JDK 21. A machine whose default `java` is 8 fails during
#     configuration, well before anything says the word "toolchain".
#
# Usage:  ./scripts/start.sh              start both, backend in the foreground
#         ./scripts/start.sh --no-ollama  skip the model server (AI_PROVIDER=dummy or gemini)
#
# Ctrl-C stops the backend, and stops Ollama too if this script was what started it. An
# Ollama that was already running is left alone — it may be serving something else.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS="${TMPDIR:-/tmp}/process-desk"
mkdir -p "$LOGS"

START_OLLAMA=1
if [ "${1:-}" = "--no-ollama" ]; then
  START_OLLAMA=0
fi

# --- .env ---------------------------------------------------------------------------------
# Only read here for the pre-flight checks. The backend gets .env from build.gradle, which
# loads it into bootRun's environment; this script deliberately does not re-export it, so
# there is one loader rather than two that can disagree.
env_value() {
  local key=$1
  [ -f "$ROOT/.env" ] || return 0
  sed -n "s/^[[:space:]]*$key[[:space:]]*=[[:space:]]*//p" "$ROOT/.env" \
    | tail -1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

PROVIDER="$(env_value AI_PROVIDER)"
PROVIDER="${PROVIDER:-dummy}"
OLLAMA_MODEL_NAME="$(env_value OLLAMA_MODEL)"
OLLAMA_MODEL_NAME="${OLLAMA_MODEL_NAME:-gpt-oss:20b}"

if [ "$PROVIDER" != "ollama" ] && [ "$START_OLLAMA" = 1 ]; then
  echo "AI_PROVIDER=$PROVIDER — not starting Ollama (nothing would ask it anything)."
  START_OLLAMA=0
fi

# --- Ollama -------------------------------------------------------------------------------
OLLAMA_STARTED_HERE=0
OLLAMA_PID=""

ollama_up() { curl -sf -m 2 http://localhost:11434/api/tags >/dev/null 2>&1; }

start_ollama() {
  if ollama_up; then
    echo "Ollama already running — leaving it alone."
    return
  fi
  command -v ollama >/dev/null 2>&1 || {
    echo "Ollama is not installed, and AI_PROVIDER=ollama. https://ollama.com/download" >&2
    exit 1
  }

  # OLLAMA_MODELS pointing at an unmounted disk is the failure this script is here for. Fall
  # back to the default directory rather than refusing to start: the model may well be there,
  # and if it is not, the pull check below is what says so.
  if [ -n "${OLLAMA_MODELS:-}" ] && [ ! -d "$OLLAMA_MODELS" ]; then
    echo "OLLAMA_MODELS is set to a directory that does not exist:"
    echo "    $OLLAMA_MODELS"
    echo "  Falling back to ~/.ollama/models. Mount that disk, or drop the export from your shell profile."
    unset OLLAMA_MODELS
  fi

  echo "starting Ollama…  (log: $LOGS/ollama.log)"
  ollama serve >"$LOGS/ollama.log" 2>&1 &
  OLLAMA_PID=$!
  OLLAMA_STARTED_HERE=1

  for _ in $(seq 1 30); do
    ollama_up && break
    sleep 1
  done
  ollama_up || {
    echo "Ollama did not come up within 30s. Last lines of $LOGS/ollama.log:" >&2
    tail -5 "$LOGS/ollama.log" >&2
    exit 1
  }
}

check_model() {
  # A missing model is otherwise discovered on the first request, several seconds into a
  # conversation, as a 404 the panel reports as "I couldn't reach the local model".
  if curl -sf -m 5 http://localhost:11434/api/tags \
     | tr ',' '\n' | grep -q "\"$OLLAMA_MODEL_NAME\""; then
    echo "model $OLLAMA_MODEL_NAME is present."
  else
    echo "model $OLLAMA_MODEL_NAME is NOT pulled. Fetch it with:" >&2
    echo "    ollama pull $OLLAMA_MODEL_NAME" >&2
    exit 1
  fi
}

# --- JDK ----------------------------------------------------------------------------------
# Gradle's toolchain wants 21. Look for it before trusting whatever `java` happens to be.
# `java_home -v 21` is not to be trusted on its own: on a machine where 21 is not registered
# it can answer with whatever it does have and still exit 0, which is how a JDK 8 path came
# back for a request for 21. Every candidate is asked its own version before it is accepted.
is_21() { [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | grep -q 'version "21'; }

find_jdk21() {
  local candidate
  if [ -n "${JAVA_HOME:-}" ] && is_21 "$JAVA_HOME"; then
    echo "$JAVA_HOME"; return 0
  fi
  if [ -x /usr/libexec/java_home ]; then
    candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [ -n "$candidate" ] && is_21 "$candidate"; then
      echo "$candidate"; return 0
    fi
  fi
  for candidate in /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21 \
                   /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-21-openjdk-amd64; do
    if is_21 "$candidate"; then
      echo "$candidate"; return 0
    fi
  done
  return 1
}

# --- port ---------------------------------------------------------------------------------
# lsof exits non-zero when nothing matches, which under `set -o pipefail` is indistinguishable
# from a real error and would take the script down while reporting nothing. A free port is the
# normal case, so it has to be a success.
port_holder() { lsof -nP -iTCP:4000 -sTCP:LISTEN -t 2>/dev/null | head -1 || true; }

# --- run ----------------------------------------------------------------------------------
cleanup() {
  if [ "$OLLAMA_STARTED_HERE" = 1 ]; then
    echo
    echo "stopping the Ollama this script started…"
    [ -n "$OLLAMA_PID" ] && kill "$OLLAMA_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

if [ "$START_OLLAMA" = 1 ]; then
  start_ollama
  check_model
fi

HELD="$(port_holder || true)"
if [ -n "$HELD" ]; then
  echo "Port 4000 is already held by PID $HELD:" >&2
  ps -p "$HELD" -o command= | cut -c1-120 >&2
  echo "  Stop it first:  kill $HELD" >&2
  exit 1
fi

JDK="$(find_jdk21 || true)"
[ -n "$JDK" ] || {
  echo "No JDK 21 found, and Gradle's toolchain needs one." >&2
  echo "  macOS:  brew install openjdk@21" >&2
  exit 1
}
echo "using JDK at $JDK"

echo "starting the backend on http://localhost:4000  (provider: $PROVIDER)"
JAVA_HOME="$JDK" "$ROOT/backend/gradlew" -p "$ROOT/backend" bootRun
