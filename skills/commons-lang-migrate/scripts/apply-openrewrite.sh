#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

skill_name=commons-lang-migrate
legacy_namespace=com.atlassian.gzipfilter.org.apache.commons.lang
recipe_name=com.atlassian.dc.p9enablement.migrate.MigrateRepackagedCommonsLang
module_path=${1:?Usage: apply-openrewrite.sh <product-module-path>}
workspace=$(pwd -P)

log() { printf '[%s] %s\n' "$skill_name" "$*"; }
fail() { printf '[%s] error: %s\n' "$skill_name" "$*" >&2; exit 1; }

[[ "$module_path" != /* && "$module_path" != *..* && "$module_path" == */* ]] || \
    fail "Module path must be a relative reactor/module path: $module_path"
if [[ -n "${PRODUCT_MODULE_PATH:-}" && "$module_path" != "$PRODUCT_MODULE_PATH" ]]; then
    fail "Requested module does not match PRODUCT_MODULE_PATH: $module_path"
fi

module_directory=$(cd "$workspace/$module_path" 2>/dev/null && pwd -P) || \
    fail "Selected module does not exist: $module_path"
reactor_path=${module_path%/*}
reactor_directory=$(cd "$workspace/$reactor_path" 2>/dev/null && pwd -P) || \
    fail "Selected reactor does not exist: $reactor_path"

[[ "$module_directory" == "$workspace"/* && "$module_directory" != "$workspace" ]] || \
    fail "Selected module escapes the repository root: $module_path"
[[ "$reactor_directory" == "$workspace"/* && "$reactor_directory" != "$workspace" ]] || \
    fail "Selected reactor escapes the repository root: $reactor_path"
[[ -f "$module_directory/pom.xml" ]] || fail "Selected module is missing pom.xml: $module_path"
[[ -f "$reactor_directory/pom.xml" ]] || fail "Selected reactor is missing pom.xml: $reactor_path"

script_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
recipe_project=$(cd "$script_directory/../../../recipes/openrewrite" && pwd -P)
[[ -f "$recipe_project/pom.xml" ]] || fail "Installed OpenRewrite recipe project is missing"

maven_bin=${MAVEN_BIN:-}
if [[ -z "$maven_bin" ]]; then
    maven_bin=$(command -v mvn || true)
fi
[[ -n "$maven_bin" && -x "$maven_bin" ]] || fail "Maven is unavailable"

run_maven() {
    "$maven_bin" --batch-mode --no-transfer-progress "$@"
}

recipe_build_marker="$recipe_project/target/.recipe-catalog-installed"
if [[ ! -f "$recipe_build_marker" ]]; then
    log "Building installed OpenRewrite recipe catalog"
    run_maven -f "$recipe_project/pom.xml" \
        -DskipRecipeTests -Dmaven.test.skip=true clean install
    mkdir -p "$(dirname "$recipe_build_marker")"
    touch "$recipe_build_marker"
else
    log "Reusing installed OpenRewrite recipe catalog"
fi

log "Installing selected reactor dependencies"
run_maven -f "$reactor_directory/pom.xml" -U \
    -pl "${module_path##*/}" -am -DskipTests install

log "Applying $recipe_name to $module_path"
run_maven -f "$reactor_directory/pom.xml" -U \
    -pl "${module_path##*/}" \
    org.openrewrite.maven:rewrite-maven-plugin:6.44.0:run \
    -Drewrite.recipeArtifactCoordinates=com.atlassian.dc.p9enablement:migration-recipes:1.0.0-SNAPSHOT \
    -Drewrite.activeRecipes="$recipe_name"

log "APPLIED: migrated $legacy_namespace with $recipe_name"
