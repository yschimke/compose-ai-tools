#!/usr/bin/env python3
"""Exercise the actual audit job condition against trusted, fork and incomplete events."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import yaml

WORKFLOW = Path(os.environ.get('UID_WORKFLOW', Path(__file__).resolve().parents[1] / 'workflows/uid-design-audit-reusable.yml'))


class AuditWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.job = yaml.safe_load(WORKFLOW.read_text())['jobs']['audit']

    def eligible(self, enabled=True, event='workflow_run', conclusion='success', head='owner/app'):
        # Evaluate the committed expression's boolean/comparison subset, not a copy of its policy.
        expression = self.job['if'].strip()
        values = {'inputs.enabled': enabled, 'github.event_name': event,
                  'github.event.workflow_run.conclusion': conclusion,
                  'github.event.workflow_run.head_repository.full_name': head,
                  'github.repository': 'owner/app'}
        for key, value in sorted(values.items(), key=lambda item: -len(item[0])):
            expression = expression.replace(key, repr(value))
        expression = expression.replace('&&', ' and ').replace('||', ' or ')
        return eval(' '.join(expression.split()), {'__builtins__': {}})

    def test_only_successful_same_repository_runs_can_spend(self):
        self.assertTrue(self.eligible())
        for overrides in ({'head': 'outsider/fork'}, {'head': None}, {'head': ''},
                          {'conclusion': 'failure'}, {'conclusion': 'cancelled'},
                          {'event': 'pull_request'}, {'enabled': False}):
            with self.subTest(overrides=overrides):
                self.assertFalse(self.eligible(**overrides))

    def test_credentialed_cli_install_requires_verified_digest(self):
        installs = [s for s in self.job['steps'] if '/.github/actions/install@' in s.get('uses', '')]
        self.assertEqual(len(installs), 1)
        self.assertEqual(installs[0]['with'].get('require-digest'), 'true')

    def test_missing_key_reports_incomplete_and_fails_before_cli(self):
        step = next(s for s in self.job['steps'] if s.get('name') == 'Audit previews with the existing guidelines engine')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '_uid_audit').mkdir()
            env = {**os.environ, 'COMPOSE_PREVIEW_OPENROUTER_KEY': ''}
            result = subprocess.run(['bash', '-e', '-c', step['run']], cwd=root, env=env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            status = json.loads((root / '_uid_audit/audit-status.json').read_text())
            self.assertEqual(status['status'], 'not_run')

    def test_audit_artifact_can_be_replaced_on_retry(self):
        upload = next(s for s in self.job['steps'] if s.get('uses', '').startswith('actions/upload-artifact@'))
        self.assertIs(upload['with'].get('overwrite'), True)

    def test_guidelines_pin_verified_and_exact_bytes_preserved(self):
        step = next(s for s in self.job['steps'] if s.get('name') == 'Fetch canonical guidelines with verified digest')
        code = step['run'].split("python - <<'PYCODE'\n", 1)[1].rsplit('PYCODE', 1)[0]
        valid = json.dumps({'schema': 'compose-ui-builder/catalog-guidelines/v1', 'rules': [{'id': 'rule'}]}).encode()
        cases = [('valid', valid, hashlib.sha256(valid).hexdigest()),
                 ('mismatch', valid, '0' * 64),
                 ('oversize', b'x' * 1_048_577, hashlib.sha256(b'x' * 1_048_577).hexdigest()),
                 ('nested', json.dumps({'schema': 'compose-ui-builder/catalog-guidelines/v1', 'rules': [1], 'includes': [1]}).encode(), None)]
        for name, data, pin in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / '_uid_audit').mkdir()
                response = io.BytesIO(data)
                response.url = 'https://example.test/rules.json'
                with patch.dict(os.environ, GUIDELINES_URL=response.url, GUIDELINES_SHA256=pin or hashlib.sha256(data).hexdigest()), patch('urllib.request.urlopen', return_value=response), patch('pathlib.Path', side_effect=lambda path: root / path):
                    if name == 'valid':
                        exec(code, {})
                        self.assertEqual((root / '_uid_audit/ui-builder.guidelines.json').read_bytes(), data)
                        self.assertEqual(json.loads((root / '_uid_audit/guidelines-source.json').read_text())['sha256'], pin)
                    else:
                        with self.assertRaises(SystemExit):
                            exec(code, {})
                        self.assertFalse((root / '_uid_audit/ui-builder.guidelines.json').exists())


if __name__ == '__main__':
    unittest.main()
