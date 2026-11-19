"""Pure, offline publication policy tests: no network, gh, Gradle or secrets."""
import copy
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import release_gate as gate


class PublicationGateTest(unittest.TestCase):
    def setUp(self):
        self.props = dict(versionName='0.1.15', versionCode='15')
        self.changelog = '# Changelog\n\n## 0.1.15 — reviewed\n\n- Manual updater\n\n## 0.1.14\nOlder\n'
        self.meta = gate.metadata_values(self.props, self.changelog, 'v0.1.15', 'true')
    def test_metadata_exact_version_code_tag_and_reviewed_notes(self):
        self.assertEqual(self.meta['versionCode'], 15)
        self.assertTrue(self.meta['prerelease'])
        self.assertIn('- Manual updater', self.meta['notes'])
        self.assertNotIn('Older', self.meta['notes'])
        self.assertIn('Clean installation required', self.meta['notes'])
        self.assertEqual(gate.version_code(self.meta['notes']), 15)
        cases = [({}, self.changelog, 'v0.1.15'),
                 (dict(self.props, versionName='0.1.15-beta.1'), self.changelog, 'v0.1.15-beta.1'),
                 (self.props, self.changelog, 'main'),
                 (self.props, '## 0.1.14\nOld\n', 'v0.1.15'),
                 (self.props, '## 0.1.15\n\n## 0.1.14\nOld\n', 'v0.1.15')]
        cases += [(dict(self.props, versionCode=code), self.changelog, 'v0.1.15')
                  for code in ('0', '-1', '1.5', '015', 'true', '2147483648')]
        for props, notes, tag in cases:
            with self.subTest(props=props, tag=tag), self.assertRaises(ValueError):
                gate.metadata_values(props, notes, tag, 'true')

    def test_channel_is_explicit_dispatch_boolean_without_release_channel_property(self):
        for channel, expected in [('true', True), ('false', False)]:
            self.assertEqual(gate.metadata_values(self.props, self.changelog, 'v0.1.15', channel)['prerelease'], expected)
        for channel in (None, '', 'beta', 'True', True):
            with self.subTest(channel=channel), self.assertRaises(ValueError):
                gate.metadata_values(self.props, self.changelog, 'v0.1.15', channel)

    def test_filenames_and_public_url_match(self):
        self.assertEqual(gate.filename('0.1.15'), 'Trove-v0.1.15.apk')
        self.assertEqual(gate.PUBLIC_API, 'https://api.github.com/repos/bitmere/trove/releases')
        for version in ('../0.1.15', 'v0.1.15', '0.1', '01.1.15', '0.1.15\n', None):
            with self.subTest(version=version), self.assertRaises(ValueError):
                gate.filename(version)

    def test_full_pin_requires_exact_match(self):
        # Synthetic format fixtures, never an actual certificate identity or secret.
        pin = gate.CERT
        self.assertEqual(gate.cert_pin(pin.upper()), pin)
        for value in ('', gate.CERT[:8], '0' * 64, gate.CERT[:8] + 'z' * 56,
                      pin + '\n', ':'.join(pin[i:i+2] for i in range(0, 64, 2)), None):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'CN=Trove'):
                gate.cert_pin(value)

    def test_verify_apk_prints_actual_signer_before_comparison(self):
        # Synthetic fixture output, never an actual certificate identity.
        other = '1' * 64
        certs = ('Verified using v1 scheme (JAR signing): true\n'
                 'Signer #1 certificate DN: CN=SyntheticFixture\n'
                 f'Signer #1 certificate SHA-256 digest: {other}\n')
        pin = gate.CERT
        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory) / 'build-tools' / '36.0.0'
            tools.mkdir(parents=True)
            (tools / 'apksigner').write_text('')
            (tools / 'aapt').write_text('')
            with patch.dict(os.environ, TROVE_RELEASE_CERT_SHA256=pin, ANDROID_HOME=directory), \
                 patch.object(gate, 'run', return_value=certs), \
                 patch('sys.stdout', new_callable=io.StringIO) as out:
                with self.assertRaisesRegex(ValueError, 'APK signer differs'):
                    gate.verify_apk(self.meta, Path('fake.apk'))
        printed = out.getvalue()
        self.assertIn(other, printed)
        self.assertIn('Signer #1 certificate SHA-256 digest', printed)

    def test_verify_apk_prints_matching_signer_and_passes(self):
        pin = gate.CERT
        certs = ('Verified using v1 scheme (JAR signing): true\n'
                 f'Signer #1 certificate SHA-256 digest: {pin}\n')
        badging = ("package: name='app.trove.debrid' versionCode='15' versionName='0.1.15'\n")

        def fake_run(*args):
            return certs if 'apksigner' in args[0] else badging

        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory) / 'build-tools' / '36.0.0'
            tools.mkdir(parents=True)
            (tools / 'apksigner').write_text('')
            (tools / 'aapt').write_text('')
            apk = Path(directory) / 'fake.apk'
            apk.write_bytes(b'fake-apk')
            with patch.dict(os.environ, TROVE_RELEASE_CERT_SHA256=pin, ANDROID_HOME=directory), \
                 patch.object(gate, 'run', side_effect=fake_run), \
                 patch('sys.stdout', new_callable=io.StringIO) as out:
                gate.verify_apk(self.meta, apk)
        self.assertIn(pin, out.getvalue())
        self.assertIn(f"APK signer fingerprints: ['{pin}']", out.getvalue())

    def test_verify_apk_accepts_v2_signer_format(self):
        # Newer build-tools print the verbose --print-certs digest as
        # "V2 Signer: ..." instead of "Signer #1 ..."; the public-key
        # digest line must never be picked up. Synthetic fixtures only.
        pin = gate.CERT
        other = '2' * 64
        certs = ('Verified using v1 scheme (JAR signing): true\n'
                 f'V2 Signer: certificate SHA-256 digest: {pin}\n'
                 f'V2 Signer: public key SHA-256 digest: {other}\n')
        badging = ("package: name='app.trove.debrid' versionCode='15' versionName='0.1.15'\n")

        def fake_run(*args):
            return certs if 'apksigner' in args[0] else badging

        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory) / 'build-tools' / '36.0.0'
            tools.mkdir(parents=True)
            (tools / 'apksigner').write_text('')
            (tools / 'aapt').write_text('')
            apk = Path(directory) / 'fake.apk'
            apk.write_bytes(b'fake-apk')
            with patch.dict(os.environ, TROVE_RELEASE_CERT_SHA256=pin, ANDROID_HOME=directory), \
                 patch.object(gate, 'run', side_effect=fake_run), \
                 patch('sys.stdout', new_callable=io.StringIO) as out:
                gate.verify_apk(self.meta, apk)
        self.assertIn(f"APK signer fingerprints: ['{pin}']", out.getvalue())

    def test_verify_apk_fails_loudly_without_certificate_digest(self):
        # Output with only a public-key digest must not be treated as an
        # empty signer list to compare against the pin.
        other = '2' * 64
        certs = ('Verified using v1 scheme (JAR signing): true\n'
                 f'Signer #1 public key SHA-256 digest: {other}\n')
        pin = gate.CERT
        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory) / 'build-tools' / '36.0.0'
            tools.mkdir(parents=True)
            (tools / 'apksigner').write_text('')
            (tools / 'aapt').write_text('')
            with patch.dict(os.environ, TROVE_RELEASE_CERT_SHA256=pin, ANDROID_HOME=directory), \
                 patch.object(gate, 'run', return_value=certs), \
                 patch('sys.stdout', new_callable=io.StringIO) as out:
                with self.assertRaisesRegex(ValueError, 'No signer certificate SHA-256 digest found'):
                    gate.verify_apk(self.meta, Path('fake.apk'))
        self.assertIn(other, out.getvalue())




class ReleaseLifecycleTest(unittest.TestCase):
    """Stateful fake REST server; no live repository or certificate identity."""
    def setUp(self):
        PublicationGateTest.setUp(self)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.tag, self.source = 'v0.1.15', 'c' * 40
        self.name = gate.filename(self.meta['versionName'])
        (self.work / self.name).write_bytes(b'synthetic-apk')
        self.sha = gate.digest(self.work / self.name)
        (self.work / (self.name + '.sha256')).write_text(f'{self.sha}  {self.name}\n')
        (self.work / 'approved.txt').write_text(f'{(self.work / self.name).stat().st_size} {self.sha}\n')
        self.release = dict(id=123, tag_name=self.tag, target_commitish=self.source,
                            name='Trove ' + self.tag, body=self.meta['notes'],
                            draft=True, prerelease=True, assets=[],
                            html_url=f'{gate.RELEASES}/untagged-fixture')
        self.present = True
        self.calls, self.blobs = [], {}
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, GITHUB_REPOSITORY='bitmere/trove', GITHUB_OUTPUT=str(self.work / 'output')).start()
        patch.object(gate, 'verify_apk').start()
        patch.object(gate, 'run', return_value=self.source).start()
        patch.object(gate.subprocess, 'run', side_effect=AssertionError('Unexpected external command')).start()
        patch.object(gate, 'api', side_effect=self.server).start()
        patch.object(gate, 'download_asset', side_effect=self.download).start()

    def add_asset(self, name, data):
        asset_id = len(self.blobs) + 1
        self.blobs[asset_id] = data
        asset = dict(id=asset_id, name=name, state='uploaded', size=len(data),
                     browser_download_url=f'{gate.RELEASES}/download/{self.tag}/{name}')
        self.release['assets'].append(asset)
        return asset

    def download(self, asset, dest):
        Path(dest).write_bytes(self.blobs[asset['id']])

    def server(self, path, method='GET', payload=None, upload=None):
        self.calls.append((method, path, payload))
        root = 'repos/bitmere/trove/releases'
        if '/tags/' in path:  # The historical GET-by-tag failure.
            return None
        if path == root + '?per_page=100&page=1':
            return [copy.deepcopy(self.release)] if self.present else []
        if path == root + '/123/assets?per_page=100&page=1':
            return copy.deepcopy(self.release['assets'])
        if path == root + '/123' and method == 'GET':
            return copy.deepcopy(self.release)
        if path == root and method == 'POST':
            self.present = True
            self.release.update(payload)
            return copy.deepcopy(self.release)
        if path.startswith('https://uploads.github.com/' + root + '/123/assets?name='):
            return self.add_asset(Path(upload).name, Path(upload).read_bytes())
        if path == root + '/123' and method == 'PATCH':
            self.assertEqual(payload, {'draft': False, 'prerelease': self.meta['prerelease'],
                                       'make_latest': 'false' if self.meta['prerelease'] else 'true'})
            self.assertEqual(len(self.release['assets']), 2)
            self.release.update(payload, html_url=f'{gate.RELEASES}/tag/{self.tag}')
            return copy.deepcopy(self.release)
        raise AssertionError((method, path))

    def publish(self):
        gate.publish_release(self.meta, self.tag, self.source, self.work)

    def add_untagged_assets(self):
        for name in (self.name, self.name + '.sha256'):
            asset = self.add_asset(name, (self.work / name).read_bytes())
            asset['browser_download_url'] = f'{gate.RELEASES}/download/untagged-fd2fa925072f63caf707/{name}'
        return self.release['assets']

    def test_validate_assets_accepts_untagged_urls_only_for_drafts(self):
        assets = self.add_untagged_assets()
        self.assertEqual(gate.validate_assets(assets, self.tag, draft=True), assets)
        with self.assertRaisesRegex(ValueError, 'Unexpected browser download URL'):
            gate.validate_assets(assets, self.tag, draft=False)

    def test_release_state_finds_draft_by_real_tag_with_untagged_asset_urls(self):
        assets = self.add_untagged_assets()
        release = gate.release_state(self.meta, self.tag, self.source)
        self.assertEqual(release['id'], self.release['id'])
        self.assertEqual(release['tag_name'], self.tag)
        self.assertTrue(release['draft'])
        self.assertEqual(release['assets'], assets)

    def test_untagged_draft_uploads_to_existing_id_and_undrafts(self):
        self.assertIsNone(self.server('repos/bitmere/trove/releases/tags/' + self.tag))
        self.publish()
        self.assertFalse(self.release['draft'])
        self.assertEqual([a['name'] for a in self.release['assets']], [self.name, self.name + '.sha256'])
        self.assertFalse(any(method == 'POST' and path.endswith('/releases') for method, path, _ in self.calls))
        self.assertEqual(sum(method == 'PATCH' for method, _, _ in self.calls), 1)
        self.assertFalse(any(method == 'DELETE' for method, _, _ in self.calls))

    def test_create_captures_id_and_published_rerun_is_read_only(self):
        self.present = False
        self.publish()
        self.calls.clear()
        self.publish()
        self.assertTrue(all(method == 'GET' for method, _, _ in self.calls))

    def test_lost_create_response_recovers_server_side_draft(self):
        self.present = False
        original = self.server
        def lost_response(path, method='GET', payload=None, upload=None):
            value = original(path, method, payload, upload)
            if method == 'POST' and path.endswith('/releases'):
                raise gate.APIError('synthetic transport failure')
            return value
        with patch.object(gate, 'api', side_effect=lost_response):
            self.publish()
        self.assertFalse(self.release['draft'])

    def test_partial_upload_reuses_exact_apk(self):
        self.add_asset(self.name, (self.work / self.name).read_bytes())
        gate.recover(self.meta, self.tag, self.source, self.work)
        self.assertEqual((self.work / 'output').read_text(), 'reuse=true\n')
        self.publish()
        self.assertEqual(sum(method == 'POST' for method, _, _ in self.calls), 1)

    def test_existing_divergent_asset_is_never_overwritten_or_published(self):
        self.add_asset(self.name, b'different')
        with self.assertRaisesRegex(ValueError, 'never overwrite'):
            self.publish()
        self.assertTrue(self.release['draft'])
        self.assertTrue(all(method == 'GET' for method, _, _ in self.calls))

    def test_uploaded_bytes_read_back_before_publish(self):
        with patch.object(gate, 'download_asset', side_effect=lambda a, dest: Path(dest).write_bytes(b'corrupt')):
            with self.assertRaisesRegex(ValueError, 'never overwrite'):
                self.publish()
        self.assertTrue(self.release['draft'])

    def test_published_missing_assets_rejected_without_upload(self):
        self.release.update(draft=False, html_url=f'{gate.RELEASES}/tag/{self.tag}')
        with self.assertRaisesRegex(ValueError, 'Published release missing'):
            self.publish()
        self.assertTrue(all(method == 'GET' for method, _, _ in self.calls))

    def test_recovery_bootstrap_and_incomplete_draft(self):
        gate.recover(self.meta, self.tag, self.source, self.work)
        self.assertEqual((self.work / 'output').read_text(), 'reuse=false\n')
        self.add_asset(self.name + '.sha256', (self.work / (self.name + '.sha256')).read_bytes())
        with self.assertRaisesRegex(ValueError, 'no APK'):
            gate.recover(self.meta, self.tag, self.source, self.work)

    def test_published_recovery_never_rebuilds_missing_apk(self):
        self.release.update(draft=False, html_url=f'{gate.RELEASES}/tag/{self.tag}')
        with self.assertRaisesRegex(ValueError, 'refusing rebuild'):
            gate.recover(self.meta, self.tag, self.source, self.work)

    def test_recover_draft_and_published_apk_checks_companion(self):
        apk = self.work / self.name
        sidecar = self.work / (self.name + '.sha256')
        self.add_asset(apk.name, apk.read_bytes())
        gate.recover(self.meta, self.tag, self.source, self.work)
        self.assertEqual((self.work / 'output').read_text(), 'reuse=true\n')
        self.release.update(draft=False, html_url=f'{gate.RELEASES}/tag/{self.tag}')
        with self.assertRaisesRegex(ValueError, 'missing checksum'):
            gate.recover(self.meta, self.tag, self.source, self.work)
        asset = self.add_asset(sidecar.name, sidecar.read_bytes())
        gate.recover(self.meta, self.tag, self.source, self.work)
        self.blobs[asset['id']] = bytes(asset['size'])
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            gate.recover(self.meta, self.tag, self.source, self.work)

    def test_new_stable_release_is_promoted_to_latest(self):
        self.present = False
        self.meta['prerelease'] = False
        self.publish()
        self.assertFalse(self.release['draft'])
        self.assertTrue((self.work / 'promoted').exists())

    def test_nullable_missing_and_divergent_release_fields_fail_closed(self):
        original = copy.deepcopy(self.release)
        for key, value in [('body', None), ('id', None), ('id', True), ('draft', None),
                           ('prerelease', None), ('name', None), ('target_commitish', 'main'),
                           ('tag_name', None), ('body', 'changed'), ('prerelease', False)]:
            with self.subTest(key=key, value=value):
                self.release = dict(original, **{key: value})
                with self.assertRaises(ValueError):
                    self.publish()
        self.release = original
        for key in ('id', 'body', 'draft', 'tag_name', 'name', 'target_commitish', 'prerelease'):
            with self.subTest(missing=key):
                self.release = {k: v for k, v in original.items() if k != key}
                with self.assertRaises(ValueError):
                    self.publish()

    def test_duplicate_releases_rejected(self):
        with patch.object(gate, 'api_list', return_value=[self.release, self.release]):
            with self.assertRaisesRegex(ValueError, 'Ambiguous'):
                self.publish()

    def test_malformed_and_duplicate_assets_rejected(self):
        good = dict(id=1, name=self.name, size=5, state='uploaded')
        for assets in ([None], [good, good], [dict(good, id=None)], [dict(good, state='starter')],
                       [dict(good, size=0)], [dict(good, name=None)]):
            with self.subTest(assets=assets):
                self.release['assets'] = assets
                with self.assertRaises(ValueError):
                    self.publish()

    def test_create_failure_without_server_side_draft_stops(self):
        self.present = False
        original = self.server
        def failed(path, method='GET', payload=None, upload=None):
            if method == 'POST':
                raise gate.APIError('synthetic denied request')
            return original(path, method, payload, upload)
        with patch.object(gate, 'api', side_effect=failed), self.assertRaises(gate.APIError):
            self.publish()
        self.assertFalse(self.present)

    def test_release_on_second_page_is_used_without_creation(self):
        original = self.server
        def pages(path, method='GET', payload=None, upload=None):
            if path == 'repos/bitmere/trove/releases?per_page=100&page=1':
                return [dict(self.release, id=1000+i, tag_name=f'v9.0.{i}') for i in range(100)]
            if path == 'repos/bitmere/trove/releases?per_page=100&page=2':
                return [copy.deepcopy(self.release)]
            return original(path, method, payload, upload)
        with patch.object(gate, 'api', side_effect=pages):
            self.publish()
        self.assertFalse(self.release['draft'])
        self.assertFalse(any(method == 'POST' and path.endswith('/releases') for method, path, _ in self.calls))

    def test_invalid_create_or_readback_id_is_rejected(self):
        self.present = False
        original = self.server
        def invalid_create(path, method='GET', payload=None, upload=None):
            if method == 'POST':
                return None
            return original(path, method, payload, upload)
        with patch.object(gate, 'api', side_effect=invalid_create), self.assertRaisesRegex(ValueError, 'release response'):
            self.publish()
        with patch.object(gate, 'api', return_value=dict(self.release, id=456)):
            with self.assertRaisesRegex(ValueError, 'ID changed'):
                gate.release_by_id(123, self.meta, self.tag, self.source)

    def test_publish_response_must_really_be_undrafted(self):
        original = self.server
        def no_effect(path, method='GET', payload=None, upload=None):
            if method == 'PATCH':
                return copy.deepcopy(self.release)
            return original(path, method, payload, upload)
        with patch.object(gate, 'api', side_effect=no_effect), self.assertRaisesRegex(ValueError, 'remains draft'):
            self.publish()



    def test_public_assets_metadata_and_download_are_required(self):
        self.publish()
        apk = self.work / self.name
        def fetch(url, limit, dest=None):
            if dest is None:
                return (self.work / (self.name + '.sha256')).read_bytes()
            dest.write_bytes(apk.read_bytes())
        with patch.object(gate, 'public_json', side_effect=[self.release, [self.release]]), \
             patch.object(gate, 'public_fetch', side_effect=fetch):
            gate.verify_public(self.meta, self.tag, self.source, self.work)
        for changes in ({'draft': True}, {'prerelease': False}, {'body': 'wrong'}, {'assets': []}):
            with patch.object(gate, 'public_json', return_value=dict(self.release, **changes)), \
                 self.assertRaises(ValueError):
                gate.verify_public(self.meta, self.tag, self.source, self.work)
        with patch.object(gate, 'public_json', return_value=self.release), \
             patch.object(gate, 'public_fetch', return_value=b'wrong'), \
             self.assertRaisesRegex(ValueError, 'Public checksum differs'):
            gate.verify_public(self.meta, self.tag, self.source, self.work)

    def test_anonymous_verification_retries_and_fails_closed(self):
        with patch.object(gate, 'verify_public', side_effect=ValueError('mismatch')) as verify, \
             patch.object(gate.time, 'sleep'), self.assertRaisesRegex(ValueError, 'bounded retries'):
            gate.anonymous(self.meta, self.tag, self.source, self.work)
        self.assertEqual(verify.call_count, 5)


class TransportAndArtifactTest(unittest.TestCase):
    setUp = PublicationGateTest.setUp

    def test_api_json_not_http_header_or_cli_format(self):
        from subprocess import CompletedProcess
        for value in ({'id': 1}, [], {'body': 'notes\nwith newline'}):
            with patch.object(gate.subprocess, 'run', return_value=CompletedProcess([], 0, json.dumps(value))) as call:
                self.assertEqual(gate.api('repos/bitmere/trove/releases'), value)
                self.assertNotIn('--include', call.call_args.args[0])
                self.assertEqual(call.call_args.kwargs['timeout'], 180)

    def test_api_errors_null_invalid_json_duplicate_fields_fail_closed(self):
        from subprocess import CompletedProcess
        for status, output in [(1, '{}'), (0, 'null'), (0, ''), (0, 'bad'), (0, '{"id":1,"id":2}')]:
            with self.subTest(status=status, output=output), \
                 patch.object(gate.subprocess, 'run', return_value=CompletedProcess([], status, output)):
                with self.assertRaises((ValueError, RuntimeError)):
                    gate.api('repos/bitmere/trove/releases')

    def test_api_timeout_becomes_recoverable_transport_error(self):
        with patch.object(gate.subprocess, 'run', side_effect=gate.subprocess.TimeoutExpired('gh', 180)):
            with self.assertRaisesRegex(gate.APIError, 'timed out'):
                gate.api('repos/bitmere/trove/releases', 'POST', {'draft': True})

    def test_api_sends_boolean_json_and_binary_file_without_field_coercion(self):
        from subprocess import CompletedProcess
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(gate.subprocess, 'run', return_value=CompletedProcess([], 0, '{}')) as call:
            gate.api('repos/bitmere/trove/releases/1', 'PATCH', {'draft': False})
            self.assertEqual(json.loads(call.call_args.kwargs['input']), {'draft': False})
            self.assertIn('Content-Type: application/json', call.call_args.args[0])
            upload = Path(directory) / 'synthetic.apk'
            upload.write_bytes(b'synthetic-apk')
            gate.api('https://uploads.github.com/repos/bitmere/trove/releases/1/assets?name=a.apk',
                     'POST', upload=upload)
            self.assertIn('Content-Type: application/octet-stream', call.call_args.args[0])
            self.assertIn(f'Content-Length: {upload.stat().st_size}', call.call_args.args[0])
            self.assertIn(str(upload), call.call_args.args[0])

    def test_release_and_asset_lists_paginate_and_reject_wrong_shapes(self):
        with patch.object(gate, 'api', side_effect=[[{'id': i} for i in range(100)], [{'id': 101}]]) as call:
            self.assertEqual(len(gate.api_list('repos/bitmere/trove/releases')), 101)
            self.assertTrue(call.call_args.args[0].endswith('page=2'))
        for value in (None, {}, [None], ['bad']):
            with self.subTest(value=value), patch.object(gate, 'api', return_value=value):
                with self.assertRaises(ValueError):
                    gate.api_list('repos/bitmere/trove/releases')

    def test_download_checks_size_and_optional_digest(self):
        data = b'synthetic-apk'
        def download(args, stdout, **kwargs):
            stdout.write(data)
        with tempfile.TemporaryDirectory() as directory, \
             patch.dict(os.environ, GITHUB_REPOSITORY='bitmere/trove'), \
             patch.object(gate.subprocess, 'run', side_effect=download):
            path = Path(directory) / 'asset'
            asset = dict(id=1, size=len(data))
            gate.download_asset(asset, path)
            gate.download_asset(dict(asset, digest='sha256:' + gate.digest(path)), path)
            for changes in ({'size': 100}, {'digest': 'sha256:' + '0' * 64}):
                with self.assertRaises(ValueError):
                    gate.download_asset(dict(asset, **changes), path)

    def apk_check(self, certs, badging, extra_tools=()):
        pin = gate.CERT
        with tempfile.TemporaryDirectory() as directory:
            for version, tools in [('36.0.0', ['apksigner', 'aapt'])] + list(extra_tools):
                path = Path(directory) / 'build-tools' / version
                path.mkdir(parents=True)
                for tool in tools:
                    (path / tool).touch()
            apk = Path(directory) / 'synthetic.apk'
            apk.write_bytes(b'fake')
            calls = []
            def fake_run(*args):
                calls.append(args)
                return certs if 'apksigner' in args[0] else badging
            with patch.dict(os.environ, TROVE_RELEASE_CERT_SHA256=pin, ANDROID_HOME=directory), \
                 patch.object(gate, 'run', side_effect=fake_run), patch('sys.stdout', new_callable=io.StringIO):
                gate.verify_apk(self.meta, apk)
            return calls

    def test_public_connection_has_no_inherited_auth_and_checks_size(self):
        response = io.BytesIO(b'abc')
        response.status = 200
        url = f'{gate.PUBLIC_API}/latest'
        with patch.dict(os.environ, GH_TOKEN='fixture', GITHUB_TOKEN='fixture'), \
             patch.object(gate.urllib.request, 'build_opener') as build:
            build.return_value.open.return_value = response
            self.assertEqual(gate.public_fetch(url, 3), b'abc')
            request = build.return_value.open.call_args.args[0]
            self.assertEqual(dict(request.header_items()), {'Cache-control': 'no-cache'})
            self.assertEqual(build.call_args.args[0].proxies, {})

    def test_public_redirect_only_accepts_validated_release_cdn(self):
        handler = gate.PublicRedirect()
        request = gate.urllib.request.Request(f'{gate.RELEASES}/download/v0.1.18/Trove-v0.1.18.apk')
        cdn = 'https://release-assets.githubusercontent.com/assets/apk?sig=fixture'
        self.assertEqual(handler.redirect_request(request, None, 302, '', {}, cdn).full_url, cdn)
        for url in (request.full_url, cdn.replace('https:', 'http:'), cdn.replace('.com', '.com.invalid'),
                    cdn.replace('://', '://user@'), cdn.replace('.com', '.com:8443')):
            with self.assertRaises(ValueError):
                handler.redirect_request(request, None, 302, '', {}, url)

    def test_repeated_scheme_certificate_and_reordered_badging_fields(self):
        pin = gate.CERT
        certs = f'V2 Signer: certificate SHA-256 digest: {pin}\nV3 Signer: certificate SHA-256 digest: {pin.upper()}\n'
        calls = self.apk_check(certs, "package: versionName='0.1.15' platformBuildVersionName='16' name='app.trove.debrid' versionCode='15'\n",
                              [('37.0.0-rc1', ['apksigner', 'aapt']), ('38.0.0', ['apksigner'])])
        self.assertTrue(all('/36.0.0/' in args[0] for args in calls))

    def test_malformed_additional_certificate_is_not_silently_ignored(self):
        pin = gate.CERT
        badging = "package: name='app.trove.debrid' versionCode='15' versionName='0.1.15'\n"
        for value in ('b' * 63, 'b' * 65, 'unexpected-format'):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'Invalid signer certificate'):
                self.apk_check(f'Signer #1 certificate SHA-256 digest: {pin}\nSigner #2 certificate SHA-256 digest: {value}\n', badging)

    def test_distinct_signer_debug_wrong_package_and_version_rejected(self):
        pin = gate.CERT
        certs = f'Signer #1 certificate SHA-256 digest: {pin}\n'
        badging = "package: name='app.trove.debrid' versionCode='15' versionName='0.1.15'\n"
        for cert, badge in [(certs + 'Signer #2 certificate SHA-256 digest: ' + 'b' * 64, badging),
                            (certs, badging + 'application-debuggable\n'),
                            (certs, badging.replace('15', '16')), (certs, badging.replace('app.trove.debrid', 'wrong'))]:
            with self.subTest(cert=cert, badge=badge), self.assertRaises(ValueError):
                self.apk_check(cert, badge)

    def test_no_sdk_tools_is_actionable(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, ANDROID_HOME=directory,
                 TROVE_RELEASE_CERT_SHA256=gate.CERT):
            with self.assertRaisesRegex(ValueError, 'complete stable'):
                gate.verify_apk(self.meta, Path('synthetic.apk'))

class HistoryTest(unittest.TestCase):
    def test_empty_bootstrap_then_global_monotonicity(self):
        with patch.dict(os.environ, GITHUB_REPOSITORY='bitmere/trove'), patch.object(gate, 'api_list') as api:
            api.return_value = []
            gate.check_history({'versionCode': 18}, 'v0.1.18')
            published = dict(draft=False, prerelease=True, tag_name='v0.1.17',
                             body='Notes\nAndroid-Version-Code: 18\n')
            api.return_value = [published]
            with self.assertRaisesRegex(ValueError, 'increase'):
                gate.check_history({'versionCode': 18}, 'v0.1.18')
            gate.check_history({'versionCode': 19}, 'v0.1.18')
            with self.assertRaisesRegex(ValueError, 'trailer'):
                api.return_value = [dict(published, body='Notes')]
                gate.check_history({'versionCode': 19}, 'v0.1.18')


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        self.workflow = Path('.github/workflows/release.yml').read_text()

    def test_controller_tests_run_before_tag_checkout(self):
        first_checkout = self.workflow.index('git checkout --detach')
        self.assertLess(self.workflow.index('python3 -m unittest'), first_checkout)
        self.assertIn('cp tools/release_gate.py', self.workflow[:first_checkout])

    def test_sdk_and_signing_cache_cleanup_and_publication_order(self):
        self.assertEqual(self.workflow.count("sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0'"), 2)
        self.assertIn('cache-disabled: true', self.workflow)
        self.assertIn('--no-configuration-cache --no-build-cache', self.workflow)
        self.assertIn('if: always()\n        run: rm -f', self.workflow)
        self.assertLess(self.workflow.index('release_gate.py" verify'), self.workflow.index('release_gate.py" publish'))
        self.assertLess(self.workflow.index('release_gate.py" publish'), self.workflow.index('release_gate.py" anonymous'))
        self.assertIn("GH_TOKEN: ''", self.workflow)
        self.assertIn('cancel-in-progress: false', self.workflow)

    def test_restore_properties_preserves_escapes_unicode_and_final_continuation(self):
        import base64
        import textwrap
        block = self.workflow.split('      - name: Restore ephemeral signing material\n', 1)[1]
        script = textwrap.dedent(block.split("<<'PYTHON'\n", 1)[1].split('          PYTHON', 1)[0])
        fixtures = ['storeFile=old\nstorePassword=synthetic\\:pass\nkeyAlias=fixture\nkeyPassword=synthetic',
                    'storeFile = old\r\nstorePassword=synthetic\r\n',
                    'store\\File=old\nkeyPassword=synthetic\\',
                    'storePassword=synthétique😀\n']
        for props in fixtures:
            with self.subTest(props=props), tempfile.TemporaryDirectory() as directory, \
                 patch.dict(os.environ, RELEASE_WORK=directory, KEYSTORE=base64.b64encode(b'synthetic-keystore').decode(), PROPS=props):
                exec(script, {})
                restored = (Path(directory) / 'keystore.properties').read_text(encoding='ascii')
                self.assertTrue(restored.endswith('\n\nstoreFile=' + directory + '/release.jks\n'))
                if '😀' in props:
                    self.assertIn('\\ud83d\\ude00', restored)
                    self.assertIn('\\u00e9', restored)
                self.assertEqual((Path(directory) / 'release.jks').read_bytes(), b'synthetic-keystore')


if __name__ == '__main__':
    unittest.main()
