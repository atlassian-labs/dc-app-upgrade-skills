#!/usr/bin/env bash
# List tracked jQuery/AUI declarations for comparison with the target product.
# Usage: OUTPUT_FILE=declared-versions.json bash find-declared-jquery-versions.sh [directory]
# Exit 0 writes an inventory, not a compatibility verdict; nonzero means incomplete inspection.
set -euo pipefail

python3 - "${1:-.}" "${OUTPUT_FILE:-}" "$#" <<'PYTHON'
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

scan_path = Path(sys.argv[1]).resolve()
if int(sys.argv[3]) > 1 or not scan_path.is_dir():
    sys.exit('Error: supply one existing project directory.')

try:
    root = Path(subprocess.check_output(
        ['git', '-C', str(scan_path), 'rev-parse', '--show-toplevel'],
        stderr=subprocess.PIPE,
    ).decode().strip()).resolve()
    tracked = subprocess.check_output(
        ['git', '-C', str(root), 'ls-files', '-z', '--', str(scan_path)],
        stderr=subprocess.PIPE,
    )
except subprocess.CalledProcessError as error:
    sys.exit('Error: cannot enumerate tracked files: ' + error.stderr.decode(errors='replace'))

module = scan_path
while module != root and not any((module / name).is_file() for name in ('pom.xml', 'package.json')):
    module = module.parent
report = Path(sys.argv[2]) if sys.argv[2] else root / '.migration-state/evidence/jquery4' / module.name / 'declared-versions.json'
excluded = {'.git', '.migration-state', '.rovodev', '.agents', '.codex',
            'migration-capabilities', '.yarn', '.mvn', 'node_modules',
            'bower_components', 'vendor', 'target', 'out', 'dist'}
packages = ('jquery', '@types/jquery', '@atlassian/aui')
scopes = ('dependencies', 'devDependencies', 'peerDependencies', 'optionalDependencies')
declarations = []

for raw in sorted(set(tracked.split(b'\0')) - {b''}):
    path = Path(os.fsdecode(raw))
    if path.name not in ('package.json', 'pom.xml') or excluded.intersection(path.parts[:-1]):
        continue
    absolute = root / path
    try:
        content = absolute.read_text(encoding='utf-8')
    except FileNotFoundError:
        if os.path.lexists(absolute):
            sys.exit(f'Error: cannot read {path}: broken symlink.')
        continue  # A tracked manifest may be deleted in the working tree.
    except (OSError, UnicodeError) as error:
        sys.exit(f'Error: cannot read {path}: {error}')
    try:
        if path.name == 'package.json':
            manifest = json.loads(content)
            if not isinstance(manifest, dict):
                raise ValueError('expected a JSON object')
            for scope in scopes:
                entries = manifest.get(scope, {})
                if not isinstance(entries, dict):
                    raise ValueError(f'{scope} must be an object')
                for name in packages:
                    if name in entries:
                        if not isinstance(entries[name], str):
                            raise ValueError(f'{scope}.{name} must be a string')
                        declarations.append({'filePath': str(path), 'name': name,
                                             'scope': scope, 'declared': entries[name]})
        else:
            pom = ET.fromstring(content)
            for properties in pom.iter():
                if properties.tag.rsplit('}', 1)[-1] == 'properties':
                    for entry in properties:
                        if entry.tag.rsplit('}', 1)[-1] == 'aui.version':
                            declarations.append({'filePath': str(path), 'name': 'aui.version',
                                                 'scope': 'properties', 'declared': (entry.text or '').strip()})
    except (ValueError, ET.ParseError) as error:
        sys.exit(f'Error: cannot inspect {path}: {error}')

report.parent.mkdir(parents=True, exist_ok=True)
report.write_text(json.dumps(declarations, indent=2) + '\n', encoding='utf-8')
print(f'Wrote {len(declarations)} declarations to {report}; compare them with the target product.', file=sys.stderr)
PYTHON
