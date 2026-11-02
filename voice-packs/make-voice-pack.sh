#!/usr/bin/env bash
# Turns an extracted sherpa-onnx speech model folder into a Re:TUI Keys voice pack zip.
# See VOICE_PACKS.md for the format and supported model types.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: make-voice-pack.sh --type TYPE --id ID --name NAME [--lang CODE]... [--option KEY=VALUE]...
                          [--version N] MODEL_DIR [OUTPUT.zip]

  --type     moonshine | whisper | transducer | sense_voice
  --id       lowercase id, e.g. moonshine-base-en
  --name     label shown in keyboard settings, e.g. "English (Moonshine base)"
  --lang     language code the model handles; repeat for several
  --option   model option, e.g. language=de for Whisper or modelType=nemo_transducer for Parakeet
  --version  pack version (default 1)

Model files are found by name inside MODEL_DIR, preferring int8 variants.
EOF
  exit 1
}

type="" id="" name="" version=1 langs=() options=()
while [ $# -gt 0 ]; do
  case "$1" in
    --type) type="$2"; shift 2 ;;
    --id) id="$2"; shift 2 ;;
    --name) name="$2"; shift 2 ;;
    --lang) langs+=("$2"); shift 2 ;;
    --option) options+=("$2"); shift 2 ;;
    --version) version="$2"; shift 2 ;;
    -h|--help) usage ;;
    --*) echo "Unknown option $1" >&2; usage ;;
    *) break ;;
  esac
done
[ $# -ge 1 ] && [ -n "$type" ] && [ -n "$id" ] && [ -n "$name" ] || usage
model_dir="$1"
output="${2:-$id.zip}"
[[ "$id" =~ ^[a-z0-9][a-z0-9._-]{0,63}$ ]] || { echo "Invalid id: $id" >&2; exit 1; }
[ -d "$model_dir" ] || { echo "Not a folder: $model_dir" >&2; exit 1; }

# pick ROLE PATTERN...: first match, int8 files before full-precision ones.
roles=() files=()
pick() {
  local role="$1"; shift
  local found=""
  for pattern in "$@"; do
    found=$(cd "$model_dir" && ls -1 $pattern 2>/dev/null | grep -i 'int8' | head -n1 || true)
    [ -n "$found" ] || found=$(cd "$model_dir" && ls -1 $pattern 2>/dev/null | head -n1 || true)
    [ -n "$found" ] && break
  done
  [ -n "$found" ] || return 1
  roles+=("$role"); files+=("$found")
}
need() { pick "$@" || { echo "No $1 file found in $model_dir" >&2; exit 1; }; }

case "$type" in
  moonshine)
    need encoder 'encoder_model*.ort' 'encoder_model*.onnx' 'encode*.onnx'
    if ! pick mergedDecoder 'decoder_model_merged*.ort' 'decoder_model_merged*.onnx'; then
      need preprocessor 'preprocess*.onnx'
      need uncachedDecoder 'uncached_decode*.onnx'
      need cachedDecoder 'cached_decode*.onnx'
    fi ;;
  whisper)
    need encoder '*encoder*.onnx'
    need decoder '*decoder*.onnx' ;;
  transducer)
    need encoder '*encoder*.onnx'
    need decoder '*decoder*.onnx'
    need joiner '*joiner*.onnx' ;;
  sense_voice)
    need model 'model*.onnx' ;;
  *) echo "Unsupported type: $type" >&2; exit 1 ;;
esac
need tokens '*tokens.txt'

json_string() { printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"; }
join() { local IFS=,; echo "$*"; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

file_entries=()
for i in "${!roles[@]}"; do
  file_entries+=("$(json_string "${roles[$i]}"): $(json_string "${files[$i]}")")
  cp "$model_dir/${files[$i]}" "$work/"
done
lang_entries=()
for lang in ${langs[@]+"${langs[@]}"}; do lang_entries+=("$(json_string "$lang")"); done
option_entries=()
for option in ${options[@]+"${options[@]}"}; do
  option_entries+=("$(json_string "${option%%=*}"): $(json_string "${option#*=}")")
done

cat > "$work/manifest.json" <<EOF
{
  "schema": 1,
  "kind": "voice",
  "id": $(json_string "$id"),
  "name": $(json_string "$name"),
  "version": $version,
  "languages": [$(join ${lang_entries[@]+"${lang_entries[@]}"})],
  "type": $(json_string "$type"),
  "files": {$(join "${file_entries[@]}")},
  "options": {$(join ${option_entries[@]+"${option_entries[@]}"})}
}
EOF
for extra in LICENSE NOTICE README.md; do
  [ -f "$model_dir/$extra" ] && cp "$model_dir/$extra" "$work/"
done

output_abs="$(cd "$(dirname "$output")" && pwd)/$(basename "$output")"
rm -f "$output_abs"
# manifest.json goes first so the keyboard can identify the pack without reading the whole zip.
(cd "$work" && zip -q -X "$output_abs" manifest.json && zip -q -X "$output_abs" $(ls | grep -v '^manifest.json$'))
echo "Wrote $output_abs"
cat "$work/manifest.json"
