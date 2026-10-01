#!/usr/bin/env python3
"""Package an already signed, tested APK; never reads signing credentials."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import zipfile

CANONICAL_SIGNER = 'b9057dd649daea23390337f2c35892d0ef8591e57e9e9a27355e7cd11a841935'
ROOT = pathlib.Path(__file__).resolve().parents[1]

def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=pathlib.Path, required=True)
    parser.add_argument('--build-tools', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--published-source', required=True)
    args = parser.parse_args()
    if run('git', 'status', '--porcelain', '--untracked-files=no'):
        parser.error('Commit tracked changes before packaging a release.')
    apk = args.apk.resolve()
    cert = run(str(args.build_tools / 'apksigner'), 'verify', '--print-certs', str(apk))
    signer = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', cert)
    if signer is None or signer[1] != CANONICAL_SIGNER:
        parser.error('APK does not have the canonical installed-app signer.')
    permissions = run(str(args.build_tools / 'aapt'), 'dump', 'permissions', str(apk))
    if 'android.permission.INTERNET' in permissions:
        parser.error('Offline release must not request INTERNET.')
    badging = run(str(args.build_tools / 'aapt'), 'dump', 'badging', str(apk))
    version = re.search(r"versionCode='(\d+)' versionName='([^']+)'", badging)
    if version is None:
        parser.error('APK version metadata is unavailable.')
    gradle = (ROOT / 'app/build.gradle.kts').read_text()
    if f'versionCode = {version[1]}' not in gradle or f'versionName = "{version[2]}"' not in gradle:
        parser.error('APK version differs from checked-out source.')
    suites = []
    import xml.etree.ElementTree as ET
    for report in (ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        suites.append(ET.parse(report).getroot())
    counts = {k: sum(int(s.get(k, 0)) for s in suites) for k in ('tests', 'failures', 'errors')}
    if not suites or counts['failures'] or counts['errors']:
        parser.error('A passing unit-test report is required.')
    # Reports document validation; run tests/lint/build on this commit before calling.
    destination = args.output.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    prefix = f'PocketSteward-{version[2]}'
    provenance = {
        'versionName': version[2], 'versionCode': int(version[1]),
        'localSourceCommit': run('git', 'rev-parse', 'HEAD'),
        'publishedSourceCommit': args.published_source,
        'apkSHA256': hashlib.sha256(apk.read_bytes()).hexdigest(),
        'signerSHA256': signer[1], 'internetPermission': False,
        'unitTests': counts, 'deviceAcceptance': 'Not verified in this environment',
    }
    receipt = destination / f'{prefix}-BUILD_PROVENANCE.txt'
    receipt.write_text(json.dumps(provenance, indent=2) + '\n')
    subprocess.run(['git', 'archive', '--format=zip', '-o', str(destination / f'{prefix}-source.zip'), 'HEAD'], cwd=ROOT, check=True)
    with zipfile.ZipFile(destination / f'{prefix}.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.write(apk, f'{prefix}-arm64.apk')
        archive.write(receipt, receipt.name)
        guide_name = f'DEV{version[2].rsplit("dev", 1)[1]}_USER_GUIDE.md' if re.search(r'dev\d+$', version[2]) else 'USER_GUIDE.md'
        for guide in (guide_name, 'APP_COMPLETION_PROGRESS.md'):
            archive.write(ROOT / guide, guide)
    print(destination / f'{prefix}.zip')

if __name__ == '__main__':
    main()
