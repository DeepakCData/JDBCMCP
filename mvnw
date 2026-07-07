#!/bin/sh
# Self-provisioning Maven wrapper (macOS/Linux) — mirror of mvnw.cmd.
# Downloads Apache Maven into .mvn-dist/ on first use; no global install needed.
set -e

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MVN_VER=3.9.9
MVN_DIST="$SCRIPT_DIR/.mvn-dist"
MVN_HOME="$MVN_DIST/apache-maven-$MVN_VER"
MVN_TGZ="$MVN_DIST/apache-maven-$MVN_VER-bin.tar.gz"
MVN_URL="https://archive.apache.org/dist/maven/maven-3/$MVN_VER/binaries/apache-maven-$MVN_VER-bin.tar.gz"

if [ ! -x "$MVN_HOME/bin/mvn" ]; then
  echo "Maven $MVN_VER not found. Downloading..." >&2
  mkdir -p "$MVN_DIST"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$MVN_URL" -o "$MVN_TGZ"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$MVN_URL" -O "$MVN_TGZ"
  else
    echo "Neither curl nor wget found — install one, or install Maven manually." >&2
    exit 1
  fi
  tar -xzf "$MVN_TGZ" -C "$MVN_DIST"
  rm -f "$MVN_TGZ"
  echo "Maven downloaded to $MVN_HOME" >&2
fi

exec "$MVN_HOME/bin/mvn" "$@"
