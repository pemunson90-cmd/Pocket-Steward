#!/usr/bin/env python3
"""Publish a packaged development release and verify its public ZIP/APK hashes."""
import argparse
import base64
import hashlib
import json
import pathlib
import subprocess
import tempfile
import time
import zipfile

SIGNER = 'b9057dd649daea23390337f2c35892d0ef8591e57e9e9a27355e7cd11a841935'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def api(repository, path, payload=None, allow_missing=False):
    command = ['gh', 'api', f'repos/{repository}/{path}']
    if payload is not None:
        command += ['--method', 'POST', '--input', '-']
    for attempt in range(6):
        result = subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                                text=True, capture_output=True)
        if result.returncode and payload is not None and path == 'git/commits' and 'Object does not exist' in result.stderr and attempt < 5:
            time.sleep(2)
            continue
        break
    if result.returncode:
        if allow_missing and '(HTTP 404)' in result.stderr:
            return None
        raise RuntimeError(f'GitHub request failed: {path}: {result.stderr.strip()}')
    return json.loads(result.stdout)


def verify_zip(path, version, provenance):
    with zipfile.ZipFile(path) as archive:
        apk_name = f'PocketSteward-{version}-arm64.apk'
        receipt_name = f'PocketSteward-{version}-BUILD_PROVENANCE.txt'
        guide = f'DEV{version.rsplit("dev", 1)[1]}_USER_GUIDE.md'
        expected = {apk_name, receipt_name, guide, 'APP_COMPLETION_PROGRESS.md'}
        if set(archive.namelist()) != expected or len(archive.namelist()) != len(expected):
            raise ValueError('Unexpected release ZIP contents.')
        if json.loads(archive.read(receipt_name)) != provenance:
            raise ValueError('Embedded provenance differs from the separate receipt.')
        with archive.open(apk_name) as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != provenance['apkSHA256']:
                raise ValueError('Embedded APK hash differs from provenance.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', default='pemunson90-cmd/Pocket-Steward')
    parser.add_argument('--package-dir', type=pathlib.Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--notes-file', type=pathlib.Path, required=True)
    parser.add_argument('--receipt', type=pathlib.Path, required=True)
    args = parser.parse_args()
    import re
    if not re.fullmatch(r'\d+\.\d+\.\d+-dev\d+', args.version):
        parser.error('Use the packaged development version.')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
        parser.error('Use owner/repository.')
    folder = args.package_dir.resolve()
    prefix = f'PocketSteward-{args.version}'
    package = folder / f'{prefix}.zip'
    source_zip = folder / f'{prefix}-source.zip'
    provenance_file = folder / f'{prefix}-BUILD_PROVENANCE.txt'
    provenance = json.loads(provenance_file.read_text())
    if provenance['versionName'] != args.version or provenance['signerSHA256'] != SIGNER or provenance['internetPermission']:
        parser.error('Package version, signer or offline invariant is invalid.')
    tests = provenance['unitTests']
    if tests['tests'] <= 0 or tests['failures'] or tests['errors']:
        parser.error('Passing unit provenance is required.')
    source = provenance['publishedSourceCommit']
    if not re.fullmatch(r'[0-9a-f]{40}', source):
        parser.error('Exact published source commit is required.')
    # Prove source publication exists before creating a download branch.
    api(args.repository, f'git/commits/{source}')
    verify_zip(package, args.version, provenance)
    with zipfile.ZipFile(source_zip) as archive:
        for name in archive.namelist():
            leaf = pathlib.PurePosixPath(name).name.lower()
            if leaf.endswith(('.jks', '.keystore', '.apk')) or leaf in {'.env', 'signing-credentials.txt'}:
                parser.error('Source ZIP contains a private/build artifact.')
    entries = []
    for file in (package, source_zip, provenance_file):
        data = file.read_bytes()
        expected = hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest()
        blob = api(args.repository, 'git/blobs', {'encoding': 'base64', 'content': base64.b64encode(data).decode()})['sha']
        if blob != expected:
            raise ValueError('Uploaded artifact blob differs from local bytes.')
        entries.append({'path': file.name, 'mode': '100644', 'type': 'blob', 'sha': blob})
        print(f'Uploaded verified {file.name}', flush=True)
    branch = f'downloads/v{args.version}'
    existing = api(args.repository, f'git/ref/heads/{branch}', allow_missing=True)
    if existing:
        commit = existing['object']['sha']
        tree = api(args.repository, f'git/trees/{commit}')['tree']
        actual = {e['path']: e['sha'] for e in tree}
        if actual != {e['path']: e['sha'] for e in entries}:
            raise ValueError('This version already has different public artifacts; increase the version.')
    else:
        tree = api(args.repository, 'git/trees', {'tree': entries})['sha']
        commit = api(args.repository, 'git/commits', {'message': f'Publish verified {args.version} packages', 'tree': tree, 'parents': []})['sha']
        for attempt in range(6):
            try:
                api(args.repository, 'git/refs', {'ref': f'refs/heads/{branch}', 'sha': commit})
                break
            except RuntimeError as error:
                if 'Object does not exist' not in str(error) or attempt == 5:
                    raise
                print('Waiting for GitHub download commit visibility.', flush=True)
                time.sleep(2)
    url = f'https://raw.githubusercontent.com/{args.repository}/{commit}/{package.name}'
    with tempfile.TemporaryDirectory(prefix='pocket-public-check-') as temporary:
        public_zip = pathlib.Path(temporary) / 'download.zip'
        subprocess.run(['curl', '--fail', '--silent', '--show-error', '--location', '--retry', '3', '--output', str(public_zip), url], check=True)
        if digest(public_zip) != digest(package):
            raise ValueError('Public ZIP differs from the verified local package.')
        verify_zip(public_zip, args.version, provenance)
    receipt = {'version': args.version, 'commit': commit, 'source': source, 'url': url,
               'zipSHA256': digest(package), 'apkSHA256': provenance['apkSHA256'], 'publicHashesVerified': True}
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(receipt, indent=2) + '\n')
    print('Public ZIP and embedded APK hashes verified.', flush=True)
    tag = f'v{args.version}'
    release = api(args.repository, f'releases/tags/{tag}', allow_missing=True)
    if release is None:
        subprocess.run(['gh', 'release', 'create', tag, '--repo', args.repository, '--target', source, '--prerelease',
                        '--title', f'Pocket Steward {args.version}', '--notes-file', str(args.notes_file.resolve())], check=True)
    print(url)


if __name__ == '__main__':
    main()
