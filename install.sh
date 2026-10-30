#!/usr/bin/env bash
#
# Copy the migration catalog (skills/, subagents/, recipes/) into an agent folder,
# keeping the three directories side by side.
#
# Usage:
#   install.sh [--dry-run] [<target-project-directory>]
#
# With a target directory the catalog is installed into that project; without one it
# is installed globally under your home directory.
#
# The agent folder is chosen inside the target (or ~):
#   - every folder that already exists among .claude, .rovodev and .rovo, or
#   - .agents, if none of those exist.
#
# Only the catalog's own entries are replaced. Other skills you keep in the same
# folder are left alone, and skills removed from a newer catalog are not deleted.

set -euo pipefail

usage() {
  sed -n '3,17p' "$0" | sed 's/^# \{0,1\}//'
}

dry_run=false
target=""
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    -n|--dry-run) dry_run=true ;;
    -*) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    *)
      if [ -n "$target" ]; then
        echo "Only one target directory may be given." >&2; exit 2
      fi
      target="$1"
      ;;
  esac
  shift
done

source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
catalog_dirs="skills subagents recipes"

for name in $catalog_dirs; do
  if [ ! -d "$source_dir/$name" ]; then
    echo "Missing $source_dir/$name. Run this script from a complete checkout of the catalog." >&2
    exit 1
  fi
done

if [ -n "$target" ]; then
  if [ ! -d "$target" ]; then
    echo "Target directory does not exist: $target" >&2
    exit 1
  fi
  root="$(cd -- "$target" && pwd)"
  scope="project"
else
  root="$HOME"
  scope="global"
fi

folders=""
for candidate in .claude .rovodev .rovo; do
  if [ -d "$root/$candidate" ]; then
    folders="$folders $candidate"
  fi
done
if [ -z "$folders" ]; then
  folders=" .agents"
fi

copy_entry() { # <catalog dir> <entry> <destination agent folder>
  local dir="$1" entry="$2" dest="$3"
  if $dry_run; then
    echo "   would copy $dir/$entry"
    return
  fi
  mkdir -p "$dest/$dir"
  rm -rf "${dest:?}/$dir/${entry:?}"
  tar -C "$source_dir/$dir" --exclude=target --exclude=.DS_Store -cf - "$entry" \
    | tar -C "$dest/$dir" -xf -
}

echo "Installing the catalog ($scope) from $source_dir"
for folder in $folders; do
  dest="$root/$folder"
  echo "-> $dest"
  for dir in $catalog_dirs; do
    for path in "$source_dir/$dir"/*; do
      [ -e "$path" ] || continue
      copy_entry "$dir" "$(basename "$path")" "$dest"
    done
  done
  if [ "$folder" = ".agents" ]; then
    echo "   Note: Claude Code reads .claude/, not .agents/. Create .claude/ first and re-run to install there."
  fi
done

if $dry_run; then
  echo "Dry run only; nothing was changed."
else
  echo "Done."
fi
