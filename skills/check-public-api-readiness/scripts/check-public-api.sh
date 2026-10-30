#!/usr/bin/env bash
# Report, for every package an app bundle imports, whether the target product still publishes it
# to apps.
#
# Platform 9 replaced "export everything on the classpath, minus an exclude list" with an explicit
# allowlist. A package absent from that allowlist is unreachable from an app even though its classes
# sit on the product's classpath, and no deprecation warning precedes the change. The named library
# removals are the enumerated part; this reports the rest.
#
# Each product keeps its own allowlist in its own format, so the product's shipped copy is the only
# authority for a given build. Nothing here is derived from a snapshot.
set -euo pipefail

BUNDLE=""
PRODUCT_DIR=""
FORMAT=text

usage() {
    cat <<'USAGE'
Usage: check-public-api.sh --bundle <app.jar> --product-dir <unpacked product install>
                           [--format text|tsv]

Options:
  --bundle <path>        The app's built OSGi bundle.
  --product-dir <path>   Root of an unpacked product install, or any directory containing the
                         product jars. Searched recursively for the jar carrying the allowlist,
                         or for Jira's loose WEB-INF/classes/osgi/*.txt in an unpacked webapp.
  --format <fmt>         text (default) groups by verdict; tsv emits package<TAB>verdict<TAB>resolution.

Verdicts:
  public       named in the product's allowlist and not excluded; safe to import
  internal     on the classpath but not published to apps; an import fails to resolve
  deprecated   published and marked deprecated; scheduled to become internal
  unknown      not found in the product's export sets at all; supplied by another bundle,
               by the JDK, or not present
USAGE
}

fail() { echo "check-public-api: $*" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
    case $1 in
        --bundle) BUNDLE=${2:?"--bundle requires a path"}; shift 2 ;;
        --product-dir) PRODUCT_DIR=${2:?"--product-dir requires a path"}; shift 2 ;;
        --format) FORMAT=${2:?"--format requires a value"}; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; fail "unknown argument '$1'" ;;
    esac
done

[[ -n $BUNDLE ]] || { usage >&2; fail "--bundle is required"; }
[[ -n $PRODUCT_DIR ]] || { usage >&2; fail "--product-dir is required"; }
[[ -f $BUNDLE ]] || fail "bundle not found: $BUNDLE"
[[ -d $PRODUCT_DIR ]] || fail "product directory not found: $PRODUCT_DIR"
[[ $FORMAT == text || $FORMAT == tsv ]] || fail "--format must be text or tsv"

# Honour $TMPDIR: a sandbox may deny the system temp directory, and an unreported failure there
# would look like an empty scan.
WORK=$(mktemp -d "${TMPDIR:-/tmp}/check-public-api.XXXXXX") \
    || fail "cannot create a temporary directory under ${TMPDIR:-/tmp}; set TMPDIR to a writable directory"
trap 'rm -rf "$WORK"' EXIT
# Any other unexpected failure names itself instead of exiting with no output.
trap 'echo "check-public-api: failed at line $LINENO: $BASH_COMMAND" >&2' ERR

# ---------------------------------------------------------------------------
# The app's imports.
#
# The manifest is the right input rather than source imports: bnd has already resolved what the
# bundle actually needs, including packages reached only from an embedded library, and excluding
# packages the bundle carries itself. Continuation lines are unfolded first; a manifest wraps at 72
# bytes, mid-token, so an unfolded read is the only correct one.
# ---------------------------------------------------------------------------
unzip -p "$BUNDLE" META-INF/MANIFEST.MF 2>/dev/null \
    | tr -d '\r' \
    | sed -e ':a' -e '$!N;s/\n //;ta' -e 'P;D' \
    | grep '^Import-Package:' \
    | sed 's/^Import-Package: *//' \
    > "$WORK/raw-imports" || true

[[ -s $WORK/raw-imports ]] || fail "no Import-Package header in $BUNDLE"

# Split on commas that separate clauses, not commas inside a quoted attribute such as
# uses:="a,b". Tracking quote parity is what distinguishes the two.
awk '{
    clause = ""; inq = 0
    for (i = 1; i <= length($0); i++) {
        c = substr($0, i, 1)
        if (c == "\"") inq = !inq
        if (c == "," && !inq) { print clause; clause = ""; continue }
        clause = clause c
    }
    print clause
}' "$WORK/raw-imports" \
    | sed 's/^ *//; s/ *$//' \
    | grep -v '^$' \
    > "$WORK/clauses"

# package<TAB>resolution
awk -F';' '{
    pkg = $1
    res = "mandatory"
    if ($0 ~ /resolution:=optional/) res = "optional"
    print pkg "\t" res
}' "$WORK/clauses" | sort -u > "$WORK/imports"

# ---------------------------------------------------------------------------
# The product's allowlist.
#
# Every product resolves a package the same way -- public if it matches the allowlist and matches no
# exclude -- but each stores the two sets differently, so the extraction is per product while the
# decision below is shared.
# ---------------------------------------------------------------------------
: > "$WORK/allow"
: > "$WORK/deny"
: > "$WORK/deprecated"
: > "$WORK/exported"
: > "$WORK/notexported"
PRODUCT=""

find_jar() {
    find "$PRODUCT_DIR" -name "$1" -type f 2>/dev/null | head -1
}

# Confluence and Crowd: a YAML document with includes/excludes/deprecated sections. The section
# boundary is a column-0 key, so the extraction must stop there rather than reading to end of file.
yaml_section() {
    awk -v want="$2" '
        /^[a-z-]+:/ { inside = ($0 ~ "^" want ":") ; next }
        inside && /^[[:space:]]*-[[:space:]]*[A-Za-z]/ {
            sub(/^[[:space:]]*-[[:space:]]*/, ""); sub(/[[:space:]]*$/, ""); print
        }
    ' "$1"
}

if JAR=$(find_jar "com.atlassian.confluence.confluence-*.jar") && [[ -n ${JAR:-} ]] \
   && unzip -p "$JAR" public-api.yaml > "$WORK/api.yaml" 2>/dev/null && [[ -s $WORK/api.yaml ]]; then
    PRODUCT="Confluence"
    yaml_section "$WORK/api.yaml" includes    > "$WORK/allow"
    yaml_section "$WORK/api.yaml" excludes    > "$WORK/deny"
    yaml_section "$WORK/api.yaml" deprecated  > "$WORK/deprecated"
    if unzip -p "$JAR" package-exports.yaml > "$WORK/exp.yaml" 2>/dev/null; then
        yaml_section "$WORK/exp.yaml" includes > "$WORK/exported"
        yaml_section "$WORK/exp.yaml" excludes > "$WORK/notexported"
    fi
elif JAR=$(find_jar "jira-core-*.jar") && [[ -n ${JAR:-} ]] \
   && unzip -p "$JAR" osgi/public-packages.txt > "$WORK/allow" 2>/dev/null && [[ -s $WORK/allow ]]; then
    PRODUCT="Jira"
    unzip -p "$JAR" osgi/public-packages-excludes.txt > "$WORK/deny" 2>/dev/null || true
    unzip -p "$JAR" osgi/deprecated-packages.txt > "$WORK/deprecated" 2>/dev/null || true
    unzip -p "$JAR" osgi/package-includes.txt > "$WORK/exported" 2>/dev/null || true
    unzip -p "$JAR" osgi/package-excludes.txt > "$WORK/notexported" 2>/dev/null || true
# A Jira webapp ships the same files loose in WEB-INF/classes/osgi rather than inside jira-core.
elif ALLOW=$(find "$PRODUCT_DIR" -path '*/WEB-INF/classes/osgi/public-packages.txt' -type f 2>/dev/null | head -1) \
   && [[ -s ${ALLOW:-} ]]; then
    PRODUCT="Jira"
    JAR=$(dirname "$ALLOW")
    cp "$ALLOW" "$WORK/allow" || fail "cannot read $ALLOW"
    for pair in public-packages-excludes:deny deprecated-packages:deprecated \
                package-includes:exported package-excludes:notexported; do
        src="$JAR/${pair%%:*}.txt"
        [[ ! -f $src ]] || cp "$src" "$WORK/${pair##*:}" || fail "cannot read $src"
    done
elif JAR=$(find_jar "bitbucket-platform-*.jar") && [[ -n ${JAR:-} ]] \
   && unzip -p "$JAR" package-public-includes.properties > "$WORK/allow" 2>/dev/null && [[ -s $WORK/allow ]]; then
    PRODUCT="Bitbucket"
    unzip -p "$JAR" package-public-excludes.properties > "$WORK/deny" 2>/dev/null || true
    unzip -p "$JAR" package-includes.properties > "$WORK/exported" 2>/dev/null || true
    unzip -p "$JAR" package-excludes.properties > "$WORK/notexported" 2>/dev/null || true
elif JAR=$(find_jar "crowd-server-*.jar") && [[ -n ${JAR:-} ]] \
   && unzip -p "$JAR" com/atlassian/crowd/public-api.yaml > "$WORK/api.yaml" 2>/dev/null && [[ -s $WORK/api.yaml ]]; then
    PRODUCT="Crowd"
    yaml_section "$WORK/api.yaml" includes   > "$WORK/allow"
    yaml_section "$WORK/api.yaml" excludes   > "$WORK/deny"
    yaml_section "$WORK/api.yaml" deprecated > "$WORK/deprecated"
    if unzip -p "$JAR" com/atlassian/crowd/package-exports.yaml > "$WORK/exp.yaml" 2>/dev/null; then
        yaml_section "$WORK/exp.yaml" includes > "$WORK/exported"
        yaml_section "$WORK/exp.yaml" excludes > "$WORK/notexported"
    fi
else
    fail "no supported product allowlist found under $PRODUCT_DIR.
Bamboo keeps no allowlist resource: its set is the platform default compiled into
atlassian-plugins-osgi, plus com.atlassian.bamboo.*. Check a Bamboo app against a running
instance instead."
fi

# Strip comments and blanks, which the .txt and .properties forms carry and the YAML does not.
for f in allow deny deprecated exported notexported; do
    sed -e 's/[[:space:]]*#.*$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$WORK/$f" \
        | grep -v '^$' | sort -u > "$WORK/$f.clean" || true
    mv "$WORK/$f.clean" "$WORK/$f"
done

[[ -s $WORK/allow ]] || fail "extracted an empty allowlist from the $PRODUCT jar"

# ---------------------------------------------------------------------------
# Matching.
#
# A pattern is a dot-separated package with an optional trailing *. The wildcard matches any number
# of remaining segments, so org.apache.* covers org.apache.commons.io. A pattern without a wildcard
# matches that package exactly and no sub-package, which is why a list carries both org.apache.xml
# and org.apache.xml.* when it means to cover the package and its descendants. No shipped list uses
# a wildcard anywhere but the final segment.
# ---------------------------------------------------------------------------
matches() {
    local pkg=$1 list=$2
    awk -v pkg="$pkg" '
        {
            pat = $0
            if (pat == pkg) { found = 1; exit }
            if (pat ~ /\*$/) {
                prefix = substr(pat, 1, length(pat) - 1)         # keeps the trailing dot
                if (substr(pkg, 1, length(prefix)) == prefix) { found = 1; exit }
                if (pkg == substr(prefix, 1, length(prefix) - 1)) { found = 1; exit }
            }
        }
        END { exit !found }
    ' "$list"
}

: > "$WORK/verdicts"
while IFS=$'\t' read -r pkg res; do
    [[ -n $pkg ]] || continue
    # Exported at all: the scanner only publishes a package that matches its include set and no
    # exclude. A package outside that set reaches an app from another bundle, from the JDK, or not
    # at all, and the public allowlist has no bearing on it.
    exported=no
    if [[ -s $WORK/exported ]]; then
        if matches "$pkg" "$WORK/exported" && ! matches "$pkg" "$WORK/notexported"; then
            exported=yes
        fi
    fi

    if matches "$pkg" "$WORK/allow" && ! matches "$pkg" "$WORK/deny"; then
        if [[ -s $WORK/deprecated ]] && matches "$pkg" "$WORK/deprecated"; then
            verdict=deprecated
        else
            verdict=public
        fi
    elif [[ $exported == yes ]]; then
        verdict=internal
    else
        verdict=unknown
    fi
    printf '%s\t%s\t%s\n' "$pkg" "$verdict" "$res" >> "$WORK/verdicts"
done < "$WORK/imports"

if [[ $FORMAT == tsv ]]; then
    sort "$WORK/verdicts"
    exit 0
fi

count() { grep -cP "\t$1\t" "$WORK/verdicts" 2>/dev/null || grep -c "	$1	" "$WORK/verdicts" 2>/dev/null || echo 0; }

if [[ -d $JAR ]]; then SOURCE=$JAR; else SOURCE=$(basename "$JAR"); fi
echo "Product:   $PRODUCT ($SOURCE)"
echo "Bundle:    $(basename "$BUNDLE")"
echo "Imports:   $(wc -l < "$WORK/verdicts" | tr -d ' ') packages; allowlist $(wc -l < "$WORK/allow" | tr -d ' ') entries, excludes $(wc -l < "$WORK/deny" | tr -d ' ')"
echo

for verdict in internal deprecated unknown public; do
    n=$(awk -F'\t' -v v="$verdict" '$2==v' "$WORK/verdicts" | wc -l | tr -d ' ')
    [[ $n -gt 0 ]] || continue
    case $verdict in
        internal)   echo "INTERNAL — on the classpath but not published to apps ($n)" ;;
        deprecated) echo "DEPRECATED — published now, scheduled to become internal ($n)" ;;
        unknown)    echo "NOT IN THE PRODUCT'S EXPORT SETS ($n)" ;;
        public)     echo "PUBLIC ($n)" ;;
    esac
    awk -F'\t' -v v="$verdict" '$2==v {printf "  %-58s %s\n", $1, $3}' "$WORK/verdicts"
    echo
done

# A mandatory import of an internal package stops the whole bundle resolving, which takes the app
# down rather than degrading one feature. That is the only verdict that fails this check.
BLOCKING=$(awk -F'\t' '$2=="internal" && $3=="mandatory"' "$WORK/verdicts" | wc -l | tr -d ' ')
if [[ $BLOCKING -gt 0 ]]; then
    echo "$BLOCKING mandatory import(s) of internal packages: this bundle will not resolve on $PRODUCT."
    exit 1
fi
echo "No mandatory import of an internal package."
