import hashlib
import json
import pathlib
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import build_signed_release
import publish_release
import publish_source


class ReleaseSafetyTests(unittest.TestCase):
    def package(self, folder, data=b'known APK', duplicate=False):
        provenance = {'apkSHA256': hashlib.sha256(b'known APK').hexdigest()}
        target = pathlib.Path(folder) / 'package.zip'
        with zipfile.ZipFile(target, 'w') as archive:
            archive.writestr('PocketSteward-1.4.0-dev11-arm64.apk', data)
            archive.writestr('PocketSteward-1.4.0-dev11-BUILD_PROVENANCE.txt', json.dumps(provenance))
            archive.writestr('DEV11_USER_GUIDE.md', 'Guide')
            archive.writestr('APP_COMPLETION_PROGRESS.md', 'Progress')
            if duplicate:
                import warnings
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore')
                    archive.writestr('DEV11_USER_GUIDE.md', 'Other guide')
        return target, provenance

    def test_verified_package_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            target, receipt = self.package(folder)
            publish_release.verify_zip(target, '1.4.0-dev11', receipt)

    def test_changed_apk_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            target, receipt = self.package(folder, data=b'different APK')
            with self.assertRaises(ValueError):
                publish_release.verify_zip(target, '1.4.0-dev11', receipt)

    def test_duplicate_zip_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            target, receipt = self.package(folder, duplicate=True)
            with self.assertRaises(ValueError):
                publish_release.verify_zip(target, '1.4.0-dev11', receipt)

    def test_forged_embedded_provenance_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            target, receipt = self.package(folder)
            with self.assertRaises(ValueError):
                publish_release.verify_zip(target, '1.4.0-dev11', dict(receipt, signerSHA256='wrong'))

    def test_wrong_signing_identity_is_rejected_without_running_build(self):
        with tempfile.TemporaryDirectory() as folder:
            bundle = pathlib.Path(folder) / 'bundle.zip'
            with zipfile.ZipFile(bundle, 'w') as archive:
                archive.writestr('pocket-steward-development.jks', b'key')
                archive.writestr('SIGNING-CREDENTIALS.txt', 'Alias: pocketsteward\nCertificate SHA-256: wrong\nStore password: synthetic\nKey password: synthetic\n')
            with self.assertRaises(ValueError), patch('subprocess.run') as run:
                build_signed_release.read_bundle(bundle)
            run.assert_not_called()

    def test_remote_app_drift_prevents_branch_update(self):
        with tempfile.TemporaryDirectory() as folder:
            message = pathlib.Path(folder) / 'message.txt'
            message.write_text('Test publication')
            def git(*args, **kwargs):
                if args[0] == 'status': return ''
                if args[0] == 'diff': return 'app/source.kt'
                if args[0] == 'ls-tree': return '100644 blob ' + 'a' * 40 + '\tapp/source.kt'
                if args[0] == 'show': return b'source'
                if args[0] == 'rev-parse': return 'b' * 40
                raise AssertionError(args)
            def api(repo, path, payload=None):
                if path.startswith('git/ref/'): return {'object': {'sha': 'c' * 40}}
                if path.startswith('git/commits/'): return {'tree': {'sha': 'd' * 40}}
                if path == 'git/trees': return {'sha': 'e' * 40}
                if path.endswith('?recursive=1'): return {'tree': [{'path': 'app', 'sha': 'f' * 40}]}
                raise AssertionError('Unexpected publication mutation: ' + path)
            argv = ['publish_source', '--baseline', '1' * 40, '--message-file', str(message), '--receipt', str(message.with_suffix('.json'))]
            with patch('sys.argv', argv), patch.object(publish_source, 'git', side_effect=git), patch.object(publish_source, 'api', side_effect=api), patch('subprocess.run') as update:
                with self.assertRaisesRegex(ValueError, 'Remote app tree differs'):
                    publish_source.main()
            update.assert_not_called()


if __name__ == '__main__':
    unittest.main()
