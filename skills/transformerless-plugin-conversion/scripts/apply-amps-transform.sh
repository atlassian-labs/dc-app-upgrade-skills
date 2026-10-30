#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf '%s\n' 'Usage: apply-amps-transform.sh --product PRODUCT [--module PATH] [--profiles P1,P2]' >&2
}

module_path=.
product=
profiles=
while (($#)); do
    case "$1" in
        --module)
            (($# >= 2)) || { printf '%s\n' '--module requires a path' >&2; exit 2; }
            module_path=$2; shift 2 ;;
        --product)
            (($# >= 2)) || { printf '%s\n' '--product requires a Maven product goal prefix' >&2; exit 2; }
            product=$2; shift 2 ;;
        --profiles)
            (($# >= 2)) || { printf '%s\n' '--profiles requires a comma-separated value' >&2; exit 2; }
            profiles=$2; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'Unknown argument: %s\n' "$1" >&2; usage; exit 2 ;;
    esac
done

[[ -n "$product" ]] || {
    printf '%s\n' '--product is required' >&2
    exit 2
}
requested_module_path=$module_path
module_path=$(cd "$requested_module_path" 2>/dev/null && pwd -P) || {
    printf 'Module directory does not exist: %s\n' "$requested_module_path" >&2; exit 2;
}
pom="$module_path/pom.xml"

maven_bin=${MAVEN_BIN:-mvn}
maven_options=(--batch-mode --no-transfer-progress -DskipAllPrompts=true)
[[ -n "$profiles" ]] && maven_options+=("-P$profiles")
goal_prefix=$product
# The generic plugin hosts whatever its configuration defaults to unless told the product.
if grep -Eiq '<artifactId>[[:space:]]*amps-maven-plugin[[:space:]]*</artifactId>' "$pom" 2>/dev/null; then
    goal_prefix=amps
    maven_options+=("-Dproduct=$product")
fi

log_directory="$module_path/target/transformerless-plugin-conversion"
mkdir -p "$log_directory"
log_file="$log_directory/amps-transform.log"
stop_log="$log_directory/amps-stop-on-failure.log"

# This locates dependencies for the selected module; it does not select modules to convert.
reactor_directory=
reactor_module=
module_name=${module_path##*/}
candidate=$(dirname "$module_path")
while [[ "$candidate" != / ]]; do
    if [[ -f "$candidate/pom.xml" ]] && grep -Eq "<module>[[:space:]]*${module_name}[[:space:]]*</module>" "$candidate/pom.xml"; then
        reactor_directory=$candidate
        reactor_module=${module_path#"$candidate"/}
        break
    fi
    candidate=$(dirname "$candidate")
done
if [[ -n "$reactor_directory" ]]; then
    install_log="$log_directory/reactor-install.log"
    (cd "$reactor_directory" && "$maven_bin" "${maven_options[@]}" \
        -DskipTests -DskipITs -pl "$reactor_module" -am install) >"$install_log" 2>&1 || {
        install_status=$?
        printf 'reactor dependency installation failed with exit status %d; see %s\n' "$install_status" "$install_log" >&2
        exit "$install_status"
    }
fi

cleanup_needed=1
cleanup() {
    local status=$?
    trap - EXIT HUP INT TERM
    if ((cleanup_needed)); then
        (cd "$module_path" && "$maven_bin" "${maven_options[@]}" \
            "$goal_prefix:stop") >"$stop_log" 2>&1 || true
    fi
    exit "$status"
}
trap cleanup EXIT HUP INT TERM

(cd "$module_path" && "$maven_bin" "${maven_options[@]}" \
    -Dwait=false -DuseSpringJava=false \
    "$goal_prefix:run" "$goal_prefix:stop" "$goal_prefix:transform") >"$log_file" 2>&1 || {
    maven_status=$?
    printf 'AMPS conversion failed with exit status %d; see %s\n' "$maven_status" "$log_file" >&2
    exit "$maven_status"
}
cleanup_needed=0
