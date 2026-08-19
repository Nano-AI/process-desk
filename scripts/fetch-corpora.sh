#!/usr/bin/env bash
#
# Fetches the BPMN and DMN files this project is tested against.
#
# The corpora are not committed: they are large, they belong to other projects, and their
# licences are not ours to re-publish. This script is committed instead, so the corpus is
# reproducible from a clean checkout. Everything it fetches is Apache 2.0.
#
# Usage:  ./scripts/fetch-corpora.sh          fetch everything
#         ./scripts/fetch-corpora.sh tck      fetch one source
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORPORA="$ROOT/corpora"
mkdir -p "$CORPORA"

# Shallow, blobless, sparse: we want a few directories of XML, not years of history.
clone() {
  local name=$1 url=$2
  shift 2
  if [ -d "$CORPORA/$name/.git" ]; then
    echo "  $name already present — skipping (delete corpora/$name to refetch)"
    return
  fi
  echo "  fetching $name…"
  git clone --quiet --depth 1 --filter=blob:none --sparse "$url" "$CORPORA/$name"
  if [ "$#" -gt 0 ]; then
    git -C "$CORPORA/$name" sparse-checkout set "$@"
  fi
}

fetch_tck() {
  # The DMN Technology Compatibility Kit: the OMG spec group's own conformance suite.
  # Every boxed expression kind, and expected results per case — so it tests evaluation,
  # not only parsing. This is the primary corpus.
  clone dmn-tck https://github.com/dmn-tck/tck.git
}

fetch_drools() {
  # How the engine we actually run is itself tested. Closest thing to "DMN as Kogito
  # expects it".
  clone drools https://github.com/apache/incubator-kie-drools.git kie-dmn
}

fetch_kogito() {
  # BPMN processes exercised by the Kogito runtime's own tests.
  clone kogito-runtimes https://github.com/apache/incubator-kie-kogito-runtimes.git jbpm api
}

case "${1:-all}" in
  tck)     fetch_tck ;;
  drools)  fetch_drools ;;
  kogito)  fetch_kogito ;;
  all)     fetch_tck; fetch_drools; fetch_kogito ;;
  *)       echo "unknown source: $1 (expected: tck | drools | kogito | all)"; exit 1 ;;
esac

echo
echo "corpora/  $(find "$CORPORA" -name '*.dmn' 2>/dev/null | wc -l | tr -d ' ') DMN, $(find "$CORPORA" \( -name '*.bpmn' -o -name '*.bpmn2' \) 2>/dev/null | wc -l | tr -d ' ') BPMN"
echo "Report on them with:  cd backend && ./gradlew surveyCorpora"
