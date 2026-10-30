import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/find-declared-jquery-versions.sh'


class DeclarationInventoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='tmp_rovo_jquery_', dir=Path.cwd())
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.report = self.root / 'report.json'
        self.tracked = []
        # Supply a read-only Git stub so tests never initialize or stage a repository.
        git = self.root / 'git'
        git.write_text('''#!/usr/bin/env python3
import os, sys
if "rev-parse" in sys.argv:
    print(os.environ["TEST_ROOT"])
else:
    sys.stdout.buffer.write(os.environ["TEST_FILES"].encode().replace(b"\\n", b"\\0"))
    sys.exit(int(os.environ.get("TEST_GIT_EXIT", "0")))
''')
        git.chmod(0o755)

    def file(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        self.tracked.append(name)

    def run_scan(self, git_exit=0):
        env = dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ['PATH'],
                   TEST_ROOT=str(self.root), TEST_FILES='\n'.join(self.tracked),
                   TEST_GIT_EXIT=str(git_exit), OUTPUT_FILE=str(self.report))
        return subprocess.run(['bash', str(SCRIPT), str(self.root)], env=env, text=True, capture_output=True)

    def test_preserves_declarations_without_guessing_ranges(self):
        self.file('frontend/package.json', json.dumps({'peerDependencies': {
            'jquery': '<4.0.0 || ^5', '@types/jquery': 'latest', '@atlassian/aui': '^11.0.2',
        }}))
        self.file('pom.xml', '<project xmlns="http://maven.apache.org/POM/4.0.0"><properties>'
                  '<!-- <aui.version>1</aui.version> --><aui.version>9.5.1</aui.version>'
                  '</properties></project>')
        self.file('node_modules/package.json', 'invalid')
        result = self.run_scan()
        self.assertEqual(result.returncode, 0, result.stderr)
        entries = json.loads(self.report.read_text())
        self.assertEqual(len(entries), 4)
        self.assertEqual({entry['declared'] for entry in entries}, {'<4.0.0 || ^5', 'latest', '^11.0.2', '9.5.1'})
        self.assertTrue(all(set(entry) == {'filePath', 'name', 'scope', 'declared'} for entry in entries))

    def test_bad_manifest_fails_without_report(self):
        for content in ('{"dependencies":{"jquery":"^3",}}', '[]', '{"dependencies":null}'):
            with self.subTest(content=content):
                self.file('package.json', content)
                result = self.run_scan()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('package.json', result.stderr)
                self.assertFalse(self.report.exists())

    def test_unreadable_manifest_fails(self):
        (self.root / 'package.json').mkdir()
        self.tracked.append('package.json')
        self.assertNotEqual(self.run_scan().returncode, 0)
        self.assertFalse(self.report.exists())

    def test_deleted_manifest_is_not_a_declaration(self):
        self.tracked.append('deleted/package.json')
        self.assertEqual(self.run_scan().returncode, 0)
        self.assertEqual(json.loads(self.report.read_text()), [])

    def test_git_failure_does_not_write_empty_report(self):
        self.assertNotEqual(self.run_scan(git_exit=1).returncode, 0)
        self.assertFalse(self.report.exists())


if __name__ == '__main__':
    unittest.main()
