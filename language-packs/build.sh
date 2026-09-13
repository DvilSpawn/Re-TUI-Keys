#!/usr/bin/env bash
# Pack a language source directory into a .retui-lang archive.
#
#   ./build.sh lt-LT lithuanian
#   ./build.sh              # rebuilds every language directory
#
# The archive name follows the release asset convention: <name>-<locale>-v<version>.
set -euo pipefail

cd "$(dirname "$0")"

build() {
    local locale="$1"
    local dir="$locale"
    [ -f "$dir/manifest.json" ] || { echo "no manifest in $dir" >&2; return 1; }

    local name version
    name=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["name"].lower().replace(" ","-"))' "$dir/manifest.json")
    version=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("version",1))' "$dir/manifest.json")

    local out="dist/$name-$locale-v$version.retui-lang"
    mkdir -p dist
    rm -f "$out"
    ( cd "$dir" && zip -q -X "../$out" manifest.json words.tsv $( [ -f NOTICE ] && echo NOTICE ) $( [ -f LICENSE ] && echo LICENSE ) )
    echo "$out"
}

if [ $# -gt 0 ]; then
    build "$1"
else
    for dir in */; do
        [ "$dir" = "dist/" ] && continue
        [ -f "${dir}manifest.json" ] || continue
        build "${dir%/}"
    done
fi
