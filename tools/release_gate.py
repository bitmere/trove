#!/usr/bin/env python3
"""Fail-closed Trove Actions release checks; never handles signing passwords."""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.parse import quote
import urllib.request
import urllib.error

REPOSITORY = 'bitmere/trove'
RELEASES = f'https://github.com/{REPOSITORY}/releases'
PUBLIC_API = f'https://api.github.com/repos/{REPOSITORY}/releases'
CERT = '4253cb4104f8ccf9bc74e02c38b4830438af11d0ba1be907da0c657dfc1b12a8'
MAX_APK = 256 * 1024 * 1024
WARNING = ('Clean installation required when moving from an older signing key. '
           'Uninstall Trove, install the signed APK from this release, and enter your API key again. '
           'Uninstalling erases local settings and the saved API key.')
VERSION = r'(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)'


def filename(version):
    require(isinstance(version, str) and re.fullmatch(VERSION, version), 'Invalid versionName')
    return f'Trove-v{version}.apk'


def cert_pin(value):
    require(isinstance(value, str) and value.lower() == CERT,
            f'Configure TROVE_RELEASE_CERT_SHA256 to {CERT} (CN=Trove, O=Trove, C=IN)')
    return value.lower()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(*args):
    return subprocess.check_output(args, text=True, timeout=180).strip()


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def metadata_values(props, changelog, tag, channel):
    version, raw_code = (props.get(key, '') for key in ('versionName', 'versionCode'))
    filename(version)
    require(re.fullmatch(r'[1-9][0-9]{0,9}', raw_code) and int(raw_code) <= 2**31 - 1, 'Invalid versionCode')
    require(tag == f'v{version}', 'Tag must exactly match versionName')
    require(channel in ('true', 'false'), 'TROVE_PRERELEASE must be true or false')
    match = re.search(r'^## ' + re.escape(version) + r'(?:[ \t][^\n]*)?\n(.*?)(?=^## |\Z)', changelog, re.M | re.S)
    require(match and match[1].strip(), 'Missing reviewed changelog entry')
    require(not any(line.lstrip().startswith('Android-Version-Code') for line in match[1].splitlines()),
            'Reserved Android-Version-Code marker in changelog')
    require(not any(line.lstrip().startswith('SHA-256:') for line in match[1].split('\n')),
            'Reserved SHA-256 marker in changelog')
    notes = f'{match[1].strip()}\n\n{WARNING}\n\nAndroid-Version-Code: {raw_code}\n'
    return dict(versionName=version, versionCode=int(raw_code), prerelease=channel == 'true', notes=notes)


def metadata(tag):
    props = dict(line.strip().split('=', 1) for line in Path('version.properties').read_text().splitlines()
                 if line.strip() and not line.lstrip().startswith('#'))
    return metadata_values(props, Path('CHANGELOG.md').read_text(), tag, os.environ.get('TROVE_PRERELEASE'))


def unique_fields(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate manifest field')
        result[key] = value
    return result


class APIError(RuntimeError):
    pass


def api(path, method='GET', payload=None, upload=None):
    # Parse only JSON, never gh's human-readable output or HTTP header formatting.
    args = ['gh', 'api', '--method', method, '-H', 'Accept: application/vnd.github+json', path]
    data = None
    if payload is not None:
        args += ['-H', 'Content-Type: application/json', '--input', '-']
        data = json.dumps(payload)
    if upload is not None:
        args += ['-H', 'Content-Type: application/octet-stream',
                 '-H', f'Content-Length: {Path(upload).stat().st_size}', '--input', str(upload)]
    try:
        result = subprocess.run(args, input=data, text=True, capture_output=True, timeout=180)
    except subprocess.TimeoutExpired as error:
        raise APIError(f'GitHub API {method} timed out for {path}; retry safely') from error
    if result.returncode:
        # Never echo authentication diagnostics or request bodies.
        raise APIError(f'GitHub API {method} failed for {path}; retry safely')
    try:
        value = json.loads(result.stdout, object_pairs_hook=unique_fields)
    except (ValueError, TypeError) as error:
        raise ValueError('Invalid GitHub API JSON response') from error
    require(value is not None, 'Null GitHub API response')
    return value


def repository():
    value = os.environ.get('GITHUB_REPOSITORY', '')
    require(value == REPOSITORY, 'Release gate requires bitmere/trove')
    return f'repos/{value}'


def api_list(path):
    values = []
    for page in range(1, 1001):
        batch = api(f'{path}?per_page=100&page={page}')
        require(isinstance(batch, list) and all(isinstance(v, dict) for v in batch),
                'Invalid GitHub API list')
        values.extend(batch)
        if len(batch) < 100:
            return values
    raise ValueError('GitHub API pagination limit exceeded')


def validate_release(release, meta, tag, source, expected_sha=None):
    require(isinstance(release, dict), 'Invalid release response')
    require(type(release.get('id')) is int and release['id'] > 0, 'Invalid release ID')
    require(type(release.get('draft')) is bool and type(release.get('prerelease')) is bool,
            'Invalid release state')
    require(release.get('target_commitish') == source, 'Release source metadata mismatch')
    require(release.get('tag_name') == tag and release['prerelease'] == meta['prerelease'],
            'Release tag/channel mismatch')
    body = release.get('body')
    require(release.get('name') == f'Trove {tag}' and isinstance(body, str), 'Release notes/title mismatch')
    sha = sha256_from_notes(body) if expected_sha is None else expected_sha
    expected_notes = meta['notes'] if sha is None else notes_with_sha256(meta['notes'], sha)
    require(body.replace('\r\n', '\n').rstrip('\n') == expected_notes.replace('\r\n', '\n').rstrip('\n'),
            'Release notes/title mismatch')
    if not release['draft']:
        require(release.get('html_url') == f'{RELEASES}/tag/{tag}', 'Unexpected release URL')
    require(run('git', 'rev-parse', f'refs/tags/{tag}^{{commit}}') == source, 'Release source mismatch')
    return release


def release_assets(release):
    assets = api_list(f'{repository()}/releases/{release["id"]}/assets')
    return validate_assets(assets, release["tag_name"], draft=release['draft'])


def validate_assets(assets, tag, *, draft):
    require(isinstance(assets, list), "Invalid release assets")
    apk = filename(tag[1:])
    names, ids = set(), set()
    for asset in assets:
        require(isinstance(asset, dict), 'Invalid release asset')
        name = asset.get('name')
        require(name == apk, 'Unexpected release asset')
        url = asset.get('browser_download_url')
        prefix = f'{RELEASES}/download/'
        draft_pattern = re.escape(prefix) + r'untagged-[A-Za-z0-9]+' + re.escape(f'/{name}')
        require(url == f'{prefix}{tag}/{name}'
                or (draft and isinstance(url, str) and re.fullmatch(draft_pattern, url)),
                'Unexpected browser download URL')
        require(type(asset.get('id')) is int and asset['id'] > 0
                and isinstance(asset.get('name'), str) and asset['name']
                and type(asset.get('size')) is int and 0 < asset['size'] <= MAX_APK
                and asset.get('state') == 'uploaded', 'Invalid or incomplete release asset; refusing replacement')
        require(asset['name'] not in names and asset['id'] not in ids, 'Duplicate release asset')
        names.add(asset['name'])
        ids.add(asset['id'])
    return assets


def release_state(meta, tag, source, expected_sha=None):
    # GET-by-tag is documented for published releases; drafts can have an
    # untagged HTML URL despite the correct pending tag_name. Enumerate all pages.
    releases = api_list(f'{repository()}/releases')
    require(all(isinstance(r.get('tag_name'), str) for r in releases), 'Invalid release tag response')
    matches = [r for r in releases if r['tag_name'] == tag]
    require(len(matches) <= 1, 'Ambiguous releases for tag; refusing publication')
    if not matches:
        return None
    release = validate_release(matches[0], meta, tag, source, expected_sha)
    return dict(release, assets=release_assets(release))


def release_by_id(release_id, meta, tag, source, expected_sha=None):
    release = validate_release(api(f'{repository()}/releases/{release_id}'), meta, tag, source, expected_sha)
    require(release['id'] == release_id, 'Release ID changed')
    return dict(release, assets=release_assets(release))


def download_asset(asset, dest):
    with open(dest, 'wb') as output:
        subprocess.run(['gh', 'api', '-H', 'Accept: application/octet-stream',
                        f'{repository()}/releases/assets/{asset["id"]}'], stdout=output,
                       check=True, timeout=180)
    require(Path(dest).stat().st_size == asset['size'], 'Release asset size mismatch')
    expected = asset.get('digest')
    if expected is not None:
        require(expected == 'sha256:' + digest(dest), 'Release asset digest mismatch')


def recover(meta, tag, source, work):
    release = release_state(meta, tag, source)
    apk = next((a for a in release['assets'] if a['name'] == filename(meta['versionName'])), None) if release else None
    if apk:
        local = work / apk['name']
        download_asset(apk, local)
        verify_apk(meta, local)
        sha = sha256_from_notes(release['body'])
        require(release['draft'] or sha is not None, 'Published release missing notes SHA-256')
        if sha is not None:
            require(sha == digest(local), 'Recovered notes SHA-256 mismatch')
    elif release:
        require(release['draft'], 'Published release missing APK; refusing rebuild')
        require(not release['assets'], 'Draft has assets but no APK; refusing divergent rebuild')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'reuse={str(apk is not None).lower()}\n')


def verify_apk(meta, apk):
    pin = cert_pin(os.environ.get('TROVE_RELEASE_CERT_SHA256', ''))
    build_tools = Path(os.environ['ANDROID_HOME']) / 'build-tools'
    candidates = [p for p in build_tools.iterdir() if re.fullmatch(r'\d+\.\d+\.\d+', p.name)
                  and all((p / tool).is_file() for tool in ('apksigner', 'aapt'))] if build_tools.is_dir() else []
    require(candidates, 'No complete stable Android build-tools installation')
    tool_dir = max(candidates, key=lambda p: tuple(map(int, p.name.split('.'))))
    certs = run(str(tool_dir / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk))
    # Diagnostic: print what actually signed the APK (and the raw apksigner
    # output) before comparing, so a mismatch can be diagnosed from the
    # release log alone. A certificate fingerprint is a public identifier.
    print('apksigner --print-certs output:')
    print(certs)
    # apksigner --print-certs formats differ by build-tools version: older
    # versions print "Signer #1 certificate SHA-256 digest: ...", newer ones
    # print "V2 Signer: certificate SHA-256 digest: ...". Match the digest
    # regardless of the prefix, and never the public-key digest line.
    signers = [value.strip() for value in re.findall(r'^.*certificate SHA-256 digest:[ \t]*(.*)$', certs, re.M)]
    print(f'APK signer fingerprints: {[s.lower() for s in signers]}')
    require(signers, 'No signer certificate SHA-256 digest found in apksigner output')
    require(all(re.fullmatch(r'[0-9a-fA-F]{64}', value) for value in signers),
            'Invalid signer certificate SHA-256 digest in apksigner output')
    require({s.lower() for s in signers} == {pin}, 'APK signer differs from approved full fingerprint')
    badging = run(str(tool_dir / 'aapt'), 'dump', 'badging', str(apk))
    package_lines = re.findall(r'^package: (.*)$', badging, re.M)
    fields = dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)='([^']*)'", package_lines[0])) if len(package_lines) == 1 else {}
    require(tuple(fields.get(k) for k in ('name', 'versionCode', 'versionName')) ==
            ('app.trove.debrid', str(meta['versionCode']), meta['versionName']), 'APK package/version mismatch')
    require(not re.search(r'^application-debuggable', badging, re.M), 'Debuggable release rejected')
    require(apk.is_file() and 0 < apk.stat().st_size <= MAX_APK, 'Missing or oversized APK')


def check_assets(release, names, work):
    for name in names:
        asset = next((a for a in release['assets'] if a['name'] == name), None)
        require(asset is not None, 'Release missing required asset')
        existing = work / ('existing-' + name)
        download_asset(asset, existing)
        require(existing.stat().st_size == (work / name).stat().st_size
                and digest(existing) == digest(work / name),
                'Existing release asset differs; never overwrite')


def publish_release(meta, tag, source, work):
    apk, size, sha = approved_artifact(meta, work)
    expected_notes = notes_with_sha256(meta['notes'], sha)
    check_history(meta, tag)
    release = release_state(meta, tag, source, sha)
    if release is None:
        try:
            created = api(f'{repository()}/releases', 'POST', dict(
                tag_name=tag, target_commitish=source, name=f'Trove {tag}',
                body=expected_notes, draft=True, prerelease=meta['prerelease']))
        except APIError:
            # Creation can succeed server-side even if the response is lost.
            release = release_state(meta, tag, source, sha)
            if release is None:
                raise
        else:
            created = validate_release(created, meta, tag, source, sha)
            require(created['draft'], 'Create did not return a draft')
            release = release_by_id(created['id'], meta, tag, source, sha)
    names = (apk.name,)
    for name in names:
        if any(a['name'] == name for a in release['assets']):
            check_assets(release, (name,), work)
        else:
            require(release['draft'], 'Published release missing required asset')
            # Address uploads and edits by ID, never by a tag or HTML pseudo-tag.
            api(f'https://uploads.github.com/{repository()}/releases/{release["id"]}/assets?name={quote(name)}',
                'POST', upload=work / name)
    release = release_by_id(release['id'], meta, tag, source, sha)
    check_assets(release, names, work)  # Read back exact uploaded bytes before un-drafting.
    if release['draft']:
        check_history(meta, tag)
        require(approved_artifact(meta, work) == (apk, size, sha), 'Approved APK changed')
        settings = {'draft': False, 'prerelease': meta['prerelease'],
                    'make_latest': 'false' if meta['prerelease'] else 'true'}
        api(f'{repository()}/releases/{release["id"]}', 'PATCH', settings)
        published = release_by_id(release['id'], meta, tag, source, sha)
        require(not published['draft'], 'Release remains draft after publication')
        check_assets(published, names, work)
        (work / 'promoted').touch()


def version_code(body):
    require(isinstance(body, str), 'Invalid release body')
    lines = body.replace('\r\n', '\n').rstrip('\n').split('\n')
    require(sum(line.lstrip().startswith('Android-Version-Code') for line in lines) == 1,
            'Missing or ambiguous Android-Version-Code trailer')
    match = re.fullmatch(r'Android-Version-Code: ([1-9][0-9]{0,18})', lines[-1])
    require(match is not None and int(match[1]) <= 2**63 - 1, 'Invalid Android-Version-Code trailer')
    return int(match[1])


def sha256_from_notes(body):
    require(isinstance(body, str), 'Invalid release body')
    lines = body.replace('\r\n', '\n').rstrip('\n').split('\n')
    count = sum(line.lstrip().startswith('SHA-256:') for line in lines)
    if count == 0:
        return None
    version_code(body)
    match = re.fullmatch(r'SHA-256: ([0-9a-f]{64})', lines[-2]) if len(lines) >= 2 else None
    require(count == 1 and match is not None, 'Invalid SHA-256 line in release notes')
    return match[1]


def notes_with_sha256(notes, sha):
    require(isinstance(sha, str) and re.fullmatch(r'[0-9a-f]{64}', sha), 'Invalid SHA-256 digest')
    version_code(notes)
    require(sha256_from_notes(notes) is None, 'Release notes already contain SHA-256')
    body = notes.replace('\r\n', '\n')
    stripped = body.rstrip('\n')
    prefix, separator, trailer = stripped.rpartition('\n')
    return f'{prefix}{separator}SHA-256: {sha}\n{trailer}' + body[len(stripped):]


def check_history(meta, tag):
    tags, codes = set(), set()
    published_target = False
    highest = 0
    for release in api_list(f'{repository()}/releases'):
        require(type(release.get('draft')) is bool and type(release.get('prerelease')) is bool,
                'Invalid release history flags')
        if release['draft']:
            continue
        name = release.get('tag_name')
        require(isinstance(name, str) and re.fullmatch('v' + VERSION, name) and name not in tags,
                'Invalid or ambiguous published release history')
        code = version_code(release.get('body'))
        require(code not in codes, 'Duplicate published Android versionCode')
        tags.add(name)
        codes.add(code)
        published_target |= name == tag
        highest = max(highest, code)
    require(published_target or meta['versionCode'] > highest, 'New release must increase Android versionCode')


def approved_artifact(meta, work):
    apk = work / filename(meta['versionName'])
    size, sha = (work / 'approved.txt').read_text().split()
    require(0 < int(size) <= MAX_APK and apk.stat().st_size == int(size) and digest(apk) == sha,
            'Approved APK changed')
    return apk, int(size), sha


class PublicRedirect(urllib.request.HTTPRedirectHandler):
    max_redirections = 3
    max_repeats = 1

    def redirect_request(self, request, fp, code, msg, headers, newurl):
        from urllib.parse import urlsplit
        url = urlsplit(newurl)
        require(not request.full_url.startswith(PUBLIC_API) and url.scheme == 'https'
                and url.hostname == 'release-assets.githubusercontent.com'
                and url.port in (None, 443) and url.netloc in (url.hostname, url.hostname + ':443')
                and not url.fragment and len(url.path) > 1, 'Unexpected public download redirect')
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def public_fetch(url, limit, dest=None):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), PublicRedirect())
    request = urllib.request.Request(url, headers={'Cache-Control': 'no-cache'})
    with opener.open(request, timeout=30) as response:
        require(response.status == 200, 'Unexpected public HTTP response')
        if dest is None:
            data = response.read(limit + 1)
            require(len(data) <= limit, 'Public response exceeds size limit')
            return data
        remaining = limit
        with dest.open('wb') as output:
            while remaining:
                block = response.read(min(65536, remaining))
                require(block, 'Truncated public APK')
                output.write(block)
                remaining -= len(block)
            require(not response.read(1), 'Public APK exceeds expected size')


def public_json(url):
    return json.loads(public_fetch(url, 8 * 1024 * 1024), object_pairs_hook=unique_fields)


def verify_public(meta, tag, source, work):
    apk, size, sha = approved_artifact(meta, work)
    release = validate_release(public_json(f'{PUBLIC_API}/tags/{tag}'), meta, tag, source, sha)
    require(not release['draft'], 'Public release is a draft')
    assets = {a['name']: a for a in validate_assets(release.get('assets'), tag, draft=release['draft'])}
    require(set(assets) == {apk.name} and assets[apk.name]['size'] == size,
            'Public release asset names or sizes differ')
    require(sha256_from_notes(release['body']) == sha, 'Public notes SHA-256 differs')
    dest = work / ('public-' + apk.name)
    public_fetch(assets[apk.name]['browser_download_url'], size, dest)
    require(dest.stat().st_size == size and digest(dest) == sha, 'Public APK differs')
    verify_apk(meta, dest)

    if meta['prerelease']:
        for page in range(1, 6):
            values = public_json(f'{PUBLIC_API}?per_page=100&page={page}')
            require(isinstance(values, list), 'Invalid public release list')
            if any(item.get('id') == release['id'] and item.get('prerelease') is True for item in values):
                return
            if len(values) < 100:
                break
        raise ValueError('Approved prerelease is absent from the public release list')
    if (work / 'promoted').exists():
        latest = public_json(f'{PUBLIC_API}/latest')
        require(latest.get('id') == release['id'] and latest.get('prerelease') is False,
                'Approved stable release is absent from latest')


def anonymous(meta, tag, source, work):
    for attempt in range(5):
        try:
            verify_public(meta, tag, source, work)
            return
        except (OSError, ValueError, http.client.HTTPException):
            if attempt < 4:
                time.sleep(2 ** attempt)
    raise ValueError('Public GitHub release verification failed after bounded retries')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'recover', 'verify', 'publish', 'anonymous'])
    args = parser.parse_args()
    tag, source = os.environ['RELEASE_TAG'], os.environ['SOURCE_SHA']
    meta = metadata(tag)
    work = Path(os.environ['RELEASE_WORK'])
    work.mkdir(parents=True, exist_ok=True)
    apk = work / filename(meta['versionName'])
    if args.command == 'prepare':
        (work / 'notes.md').write_text(meta['notes'])
        with open(os.environ['GITHUB_ENV'], 'a') as output:
            output.write(f'APK_NAME={apk.name}\n')
    elif args.command == 'recover':
        recover(meta, tag, source, work)
    elif args.command == 'verify':
        verify_apk(meta, apk)
        check_history(meta, tag)
        sha = digest(apk)
        (work / 'approved.txt').write_text(f'{apk.stat().st_size} {sha}\n')
    elif args.command == 'publish':
        publish_release(meta, tag, source, work)
    else:
        anonymous(meta, tag, source, work)
        _, size, sha = approved_artifact(meta, work)
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write(f'\nSource: https://github.com/{REPOSITORY}/commit/{source}\n'
                          f'\nTag: https://github.com/{REPOSITORY}/tree/{tag}\n'
                          f'\nRelease: {RELEASES}/tag/{tag}\n'
                          f'\nAPK: {apk.name} ({size} bytes)\n\nSHA-256: `{sha}`\n'
                          f'\nSigner SHA-256: `{CERT}`\n')


if __name__ == '__main__':
    main()
