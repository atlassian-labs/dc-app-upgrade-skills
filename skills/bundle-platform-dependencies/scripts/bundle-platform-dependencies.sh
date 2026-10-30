#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage: bundle-platform-dependencies.sh <analyze|approve|check|apply|verify|exports> [options]

Launcher options:
  --maven PATH         Maven executable (defaults to MAVEN_BIN or mvn).

Analysis/application options are forwarded to PlatformBundlingCli.
Verification options are forwarded to BundlingArtifactVerifier.
Export extraction options are forwarded to OsgiExportExtractor.
Always pass --workspace PATH. Use --help after a command for its Java CLI help.
USAGE
}

fail() { printf '[bundle-platform-dependencies] %s\n' "$*" >&2; exit 1; }

[[ $# -gt 0 ]] || { usage; exit 2; }
case "$1" in -h|--help) usage; exit 0 ;; esac
mode=$1
shift
case "$mode" in analyze|approve|check|apply|verify|exports) ;; *) usage; exit 2 ;; esac

script_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
workspace=
maven_bin=${MAVEN_BIN:-mvn}
arguments=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --workspace|--maven)
            [[ $# -ge 2 && -n "$2" ]] || fail "Missing value for $1"
            case "$1" in
                --workspace) workspace=$2; arguments+=("$1" "$2") ;;
                --maven) maven_bin=$2 ;;
            esac
            shift 2
            ;;
        *) arguments+=("$1"); shift ;;
    esac
done

[[ -n "$workspace" && -d "$workspace" ]] || fail 'An existing --workspace is required'
workspace=$(cd "$workspace" && pwd -P)
recipe_project="$script_directory/../../../recipes/openrewrite"
[[ -f "$recipe_project/pom.xml" && -d "$recipe_project/src/main" ]] || \
    fail "Missing recipe project: $recipe_project; install recipes alongside skills"
recipe_project=$(cd "$recipe_project" && pwd -P)
maven_bin=$(command -v "$maven_bin") || fail 'Maven is unavailable'
# Retain the caller's JDK when entering an application with a different local
# version-manager default: an explicit JAVA_HOME wins over the java on PATH,
# which jenv or sdkman may resolve to another JDK. Both Maven bootstrap and the
# Java CLI need JDK 21+.
if [[ -n "${JAVA_HOME:-}" ]]; then
    java_bin="$JAVA_HOME/bin/java"
    java_source=JAVA_HOME
    [[ -x "$java_bin" ]] || fail "JAVA_HOME has no bin/java: $JAVA_HOME"
else
    java_bin=$(command -v java) || fail 'Java is unavailable; set JAVA_HOME to JDK 21 or newer'
    java_source='java on PATH (JAVA_HOME is unset)'
fi
java_settings=$("$java_bin" -XshowSettings:properties -version 2>&1)
java_home=$(printf '%s\n' "$java_settings" | sed -n 's/^[[:space:]]*java.home = //p')
java_version=$(printf '%s\n' "$java_settings" | sed -n 's/^[[:space:]]*java.specification.version = //p')
[[ "$java_version" =~ ^[0-9]+$ && "$java_version" -ge 21 && -x "$java_home/bin/javac" ]] || \
    fail "Selected Java ${java_version:-unknown} at ${java_home:-$java_bin} from $java_source; set JAVA_HOME to JDK 21 or newer"
export JAVA_HOME="$java_home"
export PATH="$JAVA_HOME/bin:$PATH"
if command -v sha256sum >/dev/null; then
    hash_command=(sha256sum)
else
    command -v shasum >/dev/null || fail 'sha256sum or shasum is required'
    hash_command=(shasum -a 256)
fi

# Hash only build inputs, not generated output. A copied catalog can be read-only.
source_fingerprint=$(
    cd "$recipe_project"
    { printf '%s\n' pom.xml; find src/main -type f | LC_ALL=C sort; } |
        while IFS= read -r source_file; do
            printf '%s\n' "$source_file"
            "${hash_command[@]}" < "$source_file"
        done | "${hash_command[@]}" | awk '{print $1}'
)
tool_cache="$workspace/.migration-state/bundling-tool"
mkdir -p "$tool_cache"
tool_cache=$(cd "$tool_cache" && pwd -P)
build_directory="$tool_cache/$source_fingerprint"
classpath_file="$build_directory/target/runtime-classpath.txt"
if [[ ! -f "$build_directory/.complete" || ! -s "$classpath_file" ]]; then
    mkdir -p "$build_directory/src"
    cp "$recipe_project/pom.xml" "$build_directory/pom.xml"
    cp -R "$recipe_project/src/main" "$build_directory/src/"
    printf '[bundle-platform-dependencies] Building migration tooling\n' >&2
    "$maven_bin" --batch-mode --no-transfer-progress -f "$build_directory/pom.xml" \
        -DskipRecipeTests -Dmaven.test.skip=true package \
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
        -Dmdep.includeScope=runtime -Dmdep.outputFile="$classpath_file" \
        > "$build_directory/build.log" 2>&1 || {
            tail -n 60 "$build_directory/build.log" >&2
            fail "Tooling build failed; see $build_directory/build.log"
        }
    touch "$build_directory/.complete"
fi

runtime_classpath="$build_directory/target/classes:$(cat "$classpath_file")"
cd "$workspace"
if [[ "$mode" == exports ]]; then
    exec java -cp "$runtime_classpath" \
        com.atlassian.dc.p9enablement.migrate.OsgiExportExtractor "${arguments[@]}"
fi
if [[ "$mode" == verify ]]; then
    exec java -cp "$runtime_classpath" \
        com.atlassian.dc.p9enablement.migrate.BundlingArtifactVerifier "${arguments[@]}"
fi
exec java -cp "$runtime_classpath" \
    com.atlassian.dc.p9enablement.migrate.PlatformBundlingCli \
    "$mode" --maven "$maven_bin" "${arguments[@]}"
