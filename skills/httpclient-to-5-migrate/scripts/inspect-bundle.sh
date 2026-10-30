#!/usr/bin/env bash
# Inspect a built plugin JAR after an HttpClient 5 migration.
#
# Usage: inspect-bundle.sh <plugin.jar>
#
# Prints the bundled HttpClient 5 artifacts, the mandatory Import-Package entries,
# and the failing checks. httpclient5 and httpcore5 must each be bundled only when
# the plugin uses them. Exit 0 when every check passes, 2 when a check fails,
# 1 on usage or input errors.
set -euo pipefail

fail() { printf '[inspect-bundle] %s\n' "$*" >&2; exit 1; }

[[ $# -eq 1 ]] || fail 'Usage: inspect-bundle.sh <plugin.jar>'
jar=$1
[[ -f "$jar" ]] || fail "Missing plugin JAR: $jar"
command -v unzip >/dev/null || fail 'unzip is required'

# Optional integrations referenced by httpclient5/httpcore5/httpcore5-h2 that no
# Platform 9 product exports to apps. They must not be mandatory imports.
optional_integrations='^(org\.brotli\.dec|org\.conscrypt|com\.aayushatharva\.brotli4j(\..*)?|com\.github\.luben\.zstd(\..*)?|org\.apache\.commons\.compress\.compressors(\..*)?|jdk\.net|org\.ietf\.jgss|org\.newsclub\.net\.unix)$'
legacy_packages='^(org\.apache\.http|org\.apache\.commons\.httpclient)(\..*)?$'

listing=$(unzip -Z1 "$jar")
manifest=$(unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null) || fail "No META-INF/MANIFEST.MF in $jar"

# Unfold manifest continuation lines, then split Import-Package on commas that
# are outside quoted attribute values.
imports=$(printf '%s\n' "$manifest" | tr -d '\r' | awk '
    /^ / { line = line substr($0, 2); next }
    { if (line != "") print line; line = $0 }
    END { if (line != "") print line }' |
    sed -n 's/^Import-Package: //p' |
    awk '{
        quoted = 0; clause = ""
        for (i = 1; i <= length($0); i++) {
            c = substr($0, i, 1)
            if (c == "\"") quoted = !quoted
            if (c == "," && !quoted) { print clause; clause = "" } else clause = clause c
        }
        if (clause != "") print clause
    }')

mandatory=$(printf '%s\n' "$imports" | grep -v 'resolution:=optional' | sed 's/;.*//' | sed '/^$/d' || true)

failures=()

nested=$(printf '%s\n' "$listing" | grep -E '^META-INF/lib/(httpclient5|httpcore5)[^/]*\.jar$' || true)
client5_classes=$(printf '%s\n' "$listing" | grep -c '^org/apache/hc/client5/' || true)
core5_classes=$(printf '%s\n' "$listing" | grep -c '^org/apache/hc/core5/' || true)
printf 'HttpClient 5 nested JARs:\n%s\n' "${nested:-  (none)}"
printf 'HttpClient 5 classes at bundle root: client5 %s, core5 %s\n' "$client5_classes" "$core5_classes"
client5_bundled=false
core5_bundled=false
if [[ "$client5_classes" -gt 0 ]] || grep -q '^META-INF/lib/httpclient5-[0-9]' <<< "$nested"; then
    client5_bundled=true
fi
if [[ "$core5_classes" -gt 0 ]] || grep -q '^META-INF/lib/httpcore5-[0-9]' <<< "$nested"; then
    core5_bundled=true
fi

# Check each module against what the plugin uses: its generated imports, plus
# class references outside the client itself (a !package clause hides imports).
used=$( {
    printf '%s\n' "$imports" | sed 's/;.*//' | grep -E '^org\.apache\.hc\.(client5|core5)(\.|$)' |
        sed -E 's/^org\.apache\.hc\.(client5|core5).*/\1/' || true
    unzip -p "$jar" '*.class' -x 'org/apache/hc/*' 2>/dev/null |
        LC_ALL=C grep -a -o -E 'org/apache/hc/(client5|core5)/' | cut -d/ -f4 || true
    } | sort -u)
used_list=$(printf '%s\n' "$used" | paste -sd ' ' -)
printf 'HttpClient 5 modules used by the plugin: %s\n' "${used_list:-(none)}"
if [[ "$used" == *client5* && "$client5_bundled" == false ]]; then
    failures+=('HttpClient 5 is used but not bundled (no META-INF/lib/httpclient5-*.jar or org/apache/hc/client5 classes)')
fi
if [[ ( "$used" == *core5* || "$client5_bundled" == true ) && "$core5_bundled" == false ]]; then
    failures+=('HttpCore 5 is used but not bundled (no META-INF/lib/httpcore5-*.jar or org/apache/hc/core5 classes)')
fi

legacy_bundled=$(printf '%s\n' "$listing" |
    grep -E '^(META-INF/lib/(httpclient|httpcore|httpmime|httpasyncclient|commons-httpclient)-[0-9][^/]*\.jar|org/apache/http/|org/apache/commons/httpclient/)' |
    head -5 || true)
[[ -z "$legacy_bundled" ]] || failures+=("Legacy HttpClient content is still bundled: $(printf '%s ' $legacy_bundled)")

printf 'Mandatory imports:\n'
printf '%s\n' "${mandatory:-  (none)}" | sed 's/^/  /'

while IFS= read -r package; do
    [[ -n "$package" ]] || continue
    if [[ "$package" =~ $optional_integrations ]]; then
        failures+=("Mandatory import of optional HttpClient 5 integration: $package (add $package;resolution:=optional)")
    fi
done <<< "$mandatory"

legacy_imports=$(printf '%s\n' "$imports" | sed 's/;.*//' | grep -E "$legacy_packages" || true)
[[ -z "$legacy_imports" ]] || failures+=("Legacy HttpClient packages are still imported: $(printf '%s ' $legacy_imports)")

if [[ ${#failures[@]} -eq 0 ]]; then
    printf 'Result: PASS\n'
    exit 0
fi
printf 'Result: NEEDS-ATTENTION\n'
printf '  - %s\n' "${failures[@]}"
exit 2
