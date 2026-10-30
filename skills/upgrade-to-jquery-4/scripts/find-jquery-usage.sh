#!/usr/bin/env bash
#
# find-jquery-usage.sh — Scan source files for jQuery 1.x/2.x/3.x API usage
#                         that has been removed or changed in jQuery 4.
#
# Usage:
#   ./find-jquery-usage.sh [directory-or-file]
#
# If no argument is given the entire repository (from the current working
# directory) is scanned.
#
# Optionally collect ESLint diagnostics, then scan every supported source type
# for lexical candidates. Merge both reports without discarding ambiguous calls.
# Set JQUERY_SCAN_MODE=grep to skip the external ESLint runner.
# Set OUTPUT_FILE to override the report location.
#
# Output format (.migration-state/evidence/jquery4/<module>/candidates.json):
#   [
#     { "filePath": "relative/path/to/file.js", "apis": [".trim(", ...] },
#     ...
#   ]
#
# Only files tracked by git are included. Paths are relative to the repo root.
# The "source" and metadata fields are stripped for a minimal report.
#
# Exit codes:
#   0 — report generated
#   2 — usage / argument error

set -euo pipefail

# ── Configurable ────────────────────────────────────────────────────────────

# Lexical coverage complements ESLint, including aliases the runner may miss.
GREP_EXTENSIONS=(js jsx mjs cjs ts tsx soy vm ftl jade vmd html patch)

# Directories to always exclude.
EXCLUDE_DIRS=(
    .git
    .migration-state
    .rovodev
    .agents
    .codex
    migration-capabilities
    .yarn
    .storybook
    .mvn
    node_modules
    bower_components
    vendor
    target
    out
    dist
    venv
    __pycache__
    jmake_src
)

# ── jQuery 4 migration patterns (extended regex) ───────────────────────────
#
# These are candidate signatures, not proof that a receiver is jQuery or that
# a change is required. Include older removals and contextual jQuery 4 changes;
# leave supported APIs (including bind/on/event shorthands) alone by default.
# Generic receivers also find aliases such as jq.trim; inspect them before edits.
# See references/migration.md for classification and semantic preconditions.
PATTERNS=(
    '\.isFunction[[:space:]]*\('
    '\.isArray[[:space:]]*\('
    '\.isNumeric[[:space:]]*\('
    '\.isWindow[[:space:]]*\('
    '\.type[[:space:]]*\('
    '\.trim[[:space:]]*\('
    '\.parseJSON[[:space:]]*\('
    '\.now[[:space:]]*\('
    '\.nodeName[[:space:]]*\('
    '\.camelCase[[:space:]]*\('
    '\.unique[[:space:]]*\('
    '\.cssProps\b'
    '\.cssNumber\b'
    '\.fx\.interval\b'
    '\.andSelf[[:space:]]*\('
    '\.size[[:space:]]*\('
    '\.live[[:space:]]*\('
    '\.die[[:space:]]*\('
    '\.success[[:space:]]*\('
    '\.complete[[:space:]]*\('
    '\.error[[:space:]]*\('
    '\.load[[:space:]]*\('
    '\.unload[[:space:]]*\('
    '\.toggleClass[[:space:]]*\('
    '\.css[[:space:]]*\('
    '\.ajax[[:space:]]*\('
    '\.ajaxSetup[[:space:]]*\('
    '\.get[[:space:]]*\('
    '\.post[[:space:]]*\('
    '\.push[[:space:]]*\('
    '\.sort[[:space:]]*\('
    '\.splice[[:space:]]*\('
    '\.getStackHook\b'
    '\.expr\b'
)

# ── Helper: human-readable API name from a grep-matched line ───────────────
# Given a line of source code, extract which PATTERNS matched and return
# a compact API signature like ".bind(" or "$.trim(".
# We reuse the same PATTERNS array to do per-pattern matching.

# ── Main ────────────────────────────────────────────────────────────────────

SCAN_PATH="${1:-.}"

if [[ $# -gt 1 || ! -e "$SCAN_PATH" ]]; then
    echo "Error: path '${SCAN_PATH}' does not exist." >&2
    exit 2
fi

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || echo "")"
if [[ -z "$REPO_ROOT" ]]; then
    echo "Error: not inside a git repository." >&2
    exit 2
fi

ABS_ROOT="$(cd "$REPO_ROOT" && pwd -P)"
SCAN_PATH="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$SCAN_PATH")"
case "$SCAN_PATH" in
    "$ABS_ROOT"|"$ABS_ROOT/"*) ;;
    *) echo "Error: scan path must be inside $ABS_ROOT" >&2; exit 2 ;;
esac
MODULE_ROOT="$SCAN_PATH"
[[ -d "$MODULE_ROOT" ]] || MODULE_ROOT="$(dirname "$MODULE_ROOT")"
while [[ "$MODULE_ROOT" != "$ABS_ROOT" && ! -f "$MODULE_ROOT/pom.xml" && ! -f "$MODULE_ROOT/package.json" ]]; do
    MODULE_ROOT="$(dirname "$MODULE_ROOT")"
done
MODULE_NAME=$(printf '%s' "${MODULE_ROOT##*/}" | tr -c '[:alnum:]_-' '-')
TRACKER_FILE="${OUTPUT_FILE:-$ABS_ROOT/.migration-state/evidence/jquery4/$MODULE_NAME/candidates.json}"
mkdir -p "$(dirname "$TRACKER_FILE")"
TRACKER_FILE="$(cd "$(dirname "$TRACKER_FILE")" && pwd -P)/$(basename "$TRACKER_FILE")"

# Temp files
ESLINT_RAW=$(mktemp)
GREP_RAW=$(mktemp)
PATTERN_FILE=$(mktemp)
printf '%s\n' "${PATTERNS[@]}" > "$PATTERN_FILE"
trap 'rm -f "$ESLINT_RAW" "$GREP_RAW" "$PATTERN_FILE"' EXIT

# ── Phase 1: ESLint runner for .js files ────────────────────────────────────

echo "Phase 1: Running ESLint jQuery scanner on .js files..." >&2

case "${JQUERY_SCAN_MODE:-auto}" in
    auto|grep) ;;
    *) echo "Error: JQUERY_SCAN_MODE must be auto or grep" >&2; exit 2 ;;
esac

if [[ "${JQUERY_SCAN_MODE:-auto}" == auto && -d "$SCAN_PATH" ]]; then
    npx @atlassian/jquery-eslint-runner lint-directory \
        --directory "$SCAN_PATH" \
        --format json \
        --output "$ESLINT_RAW" 2>&1 | grep -v "^$" >&2 || true

    if ! python3 - "$ESLINT_RAW" <<'CHECK_JSON'
import json, sys
try:
    with open(sys.argv[1]) as report:
        data = json.load(report)
    valid = isinstance(data, list) and all(
        isinstance(entry, dict) and isinstance(entry.get('filePath'), str)
        and isinstance(entry.get('messages', []), list)
        and all(isinstance(message, dict) for message in entry.get('messages', []))
        for entry in data
    )
except (ValueError, OSError):
    valid = False
sys.exit(0 if valid else 1)
CHECK_JSON
    then
        echo "Warning: ESLint unavailable or invalid output; lexical coverage only." >&2
        printf '[]' > "$ESLINT_RAW"
    fi
else
    echo "ESLint skipped (grep mode or individual file); lexical coverage only." >&2
    printf '[]' > "$ESLINT_RAW"
fi

echo "Phase 2: Scanning tracked sources for lexical candidates..." >&2

# Build combined regex pattern
COMBINED=""
for p in "${PATTERNS[@]}"; do
    if [[ -n "$COMBINED" ]]; then
        COMBINED="${COMBINED}|${p}"
    else
        COMBINED="$p"
    fi
done

# Build list of git-tracked files filtered by extension
TRACKED_FILES=$(mktemp)
trap 'rm -f "$ESLINT_RAW" "$GREP_RAW" "$PATTERN_FILE" "$TRACKED_FILES"' EXIT

# Enumerate from the repository root, bounded by the requested directory/file.
# NUL delimiters preserve spaces in tracked paths.
git -C "$ABS_ROOT" ls-files -z --full-name -- "$SCAN_PATH" | while IFS= read -r -d '' f; do
    skip=false
    for dir in "${EXCLUDE_DIRS[@]}"; do
        case "$f" in
            "${dir}/"*|*"/${dir}/"*) skip=true; break ;;
        esac
    done
    $skip && continue
    for ext in "${GREP_EXTENSIONS[@]}"; do
        if [[ "$f" == *."$ext" ]]; then
            printf '%s\0' "$ABS_ROOT/$f"
            break
        fi
    done
done > "$TRACKED_FILES"

# Use -H even for a single file. Do not hide read failures as a clean scan.
while IFS= read -r -d '' file; do
    status=0
    grep -HnE "$COMBINED" "$file" >> "$GREP_RAW" || status=$?
    [[ "$status" -le 1 ]] || exit "$status"
done < "$TRACKED_FILES"

echo "Phase 2 complete." >&2

# Keep lexical candidates even when project types are missing or misleading.
# jquery-type-filter.js remains an optional review aid for a caller-selected
# tsconfig; its output must not replace this complete lexical candidate list.

# ── Phase 3: Merge into clean JSON ─────────────────────────────────────────

echo "Phase 3: Merging results into $TRACKER_FILE..." >&2

ESLINT_RAW="$ESLINT_RAW" GREP_RAW="$GREP_RAW" TRACKER_FILE="$TRACKER_FILE" PATTERN_FILE="$PATTERN_FILE" SCAN_PATH="$SCAN_PATH" python3 << 'PYTHON_SCRIPT'
import json, os, re, subprocess, sys

repo_root = subprocess.run(
    ['git', 'rev-parse', '--show-toplevel'],
    capture_output=True, text=True
).stdout.strip()

eslint_raw = os.environ['ESLINT_RAW']
grep_raw = os.environ['GREP_RAW']
tracker_file = os.environ['TRACKER_FILE']

# Check if entries are git-tracked
tracked = set(subprocess.run(
    ['git', '-C', repo_root, 'ls-files', '-z', '--full-name', '--', os.environ['SCAN_PATH']],
    capture_output=True, text=True
).stdout.split('\0'))

excluded = {'.git', '.migration-state', '.rovodev', '.agents', '.codex', 'migration-capabilities',
            '.yarn', '.storybook', '.mvn', 'node_modules', 'bower_components',
            'vendor', 'target', 'out', 'dist', 'venv', '__pycache__', 'jmake_src'}

# ── Collect results keyed by relative file path ──
results = {}  # filePath -> set of api strings

# ── Process ESLint output (Phase 1) ──
try:
    with open(eslint_raw, 'r') as f:
        eslint_data = json.load(f)
except (json.JSONDecodeError, FileNotFoundError):
    eslint_data = []

for entry in eslint_data:
    fp = entry.get('filePath', '')
    if fp and not os.path.isabs(fp):
        fp = os.path.abspath(fp)

    # Make path relative to repo root
    if fp.startswith(repo_root + '/'):
        fp = fp[len(repo_root) + 1:]

    # Skip files not tracked by git
    if fp not in tracked or excluded.intersection(fp.split('/')[:-1]):
        continue

    if any(message.get('fatal') for message in entry.get('messages', [])):
        raise SystemExit(f'ESLint could not parse {fp}; inspection is incomplete')

    # Extract API names from ESLint messages
    apis = set()
    for msg in entry.get('messages', []):
        text = msg.get('message', '')
        rule_id = msg.get('ruleId', '') or ''

        # Patterns like: ".error() event shorthand is deprecated..."
        # or "$.trim() is deprecated..."
        m = re.match(r'^(\.\w+\(\)|\$\.\w+(?:\.\w+)*\(\))', text)
        if m:
            api = m.group(1)
            # Normalize: .error() -> .error(
            api = api[:-1]  # strip trailing )
            apis.add(api)
            continue
        # Patterns like: "jQuery.isFunction() is deprecated..."
        m = re.match(r'^jQuery(\.\w+(?:\.\w+)*\(\))', text)
        if m:
            api = '$' + m.group(1)
            api = api[:-1]
            apis.add(api)
            continue
        # Fallback: use the ruleId as the API name for any unrecognized
        # message format so we never silently drop ESLint violations.
        if rule_id:
            apis.add(rule_id)

    if apis:
        results.setdefault(fp, set()).update(apis)

# ── Process grep output (Phase 2) ──
# Each line: filepath:lineno:content
# We need to figure out which patterns matched each line.

# Reuse the exact shell patterns; translate POSIX whitespace for Python.
with open(os.environ['PATTERN_FILE']) as patterns_file:
    raw_patterns = patterns_file.read().splitlines()

def regex_to_name(regex):
    """Convert grep regex to human-readable API name."""
    name = regex.replace('[[:space:]]*', '').replace(r'\$', '$').replace(r'\.', '.').replace(r'\(', '(').replace(r'\b', '')
    return name

# Compile the shared patterns once.
seen = set()
compiled_patterns = []
for p in raw_patterns:
    name = regex_to_name(p)
    if name not in seen:
        seen.add(name)
        compiled_patterns.append((name, re.compile(p.replace('[[:space:]]', r'\s'))))

with open(grep_raw, 'r') as f:
    for line in f:
        line = line.rstrip('\n')
        # Parse filepath:lineno:content
        parts = line.split(':', 2)
        if len(parts) < 3:
            continue
        fp = parts[0]
        content = parts[2]

        # Make relative
        if fp.startswith(repo_root + '/'):
            fp = fp[len(repo_root) + 1:]
        fp = fp.removeprefix('./')

        # Find which APIs matched
        apis = set()
        for name, regex in compiled_patterns:
            if regex.search(content):
                apis.add(name)

        if apis:
            results.setdefault(fp, set()).update(apis)

# ── Build final output ──
output = []
for fp in sorted(results.keys()):
    output.append({
        'filePath': fp,
        'apis': sorted(results[fp])
    })

output = [e for e in output if e['filePath'] in tracked
          and not excluded.intersection(e['filePath'].split('/')[:-1])]

with open(tracker_file, 'w') as f:
    json.dump(output, f, indent=2)

print(f'Wrote {len(output)} candidate files (unconfirmed) to {tracker_file}', file=sys.stderr)
PYTHON_SCRIPT

echo "Done! Report written to $TRACKER_FILE" >&2
