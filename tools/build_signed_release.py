#!/usr/bin/env python3
"""Build and verify the offline APK using a private canonical signing ZIP."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
CANONICAL_SIGNER = 'b9057dd649daea23390337f2c35892d0ef8591e57e9e9a27355e7cd11a841935'


def read_bundle(path):
    with zipfile.ZipFile(path) as archive:
        if len(archive.infolist()) > 64:
            raise ValueError('Unexpected signing bundle contents.')
        for name, maximum in (('pocket-steward-development.jks', 1_048_576), ('SIGNING-CREDENTIALS.txt', 16_384)):
            info = archive.getinfo(name)
            if not 0 < info.file_size <= maximum or archive.namelist().count(name) != 1:
                raise ValueError('Missing, oversized or duplicated signing bundle entry.')
        fields = {}
        for line in archive.read('SIGNING-CREDENTIALS.txt').decode('utf-8').splitlines():
            if ':' in line:
                name, value = line.split(':', 1)
                fields[name.strip().lower()] = value.strip()
        claimed = fields.get('certificate sha-256', '').replace(':', '').lower()
        if claimed != CANONICAL_SIGNER or fields.get('alias') != 'pocketsteward':
            raise ValueError('Signing bundle does not identify the canonical app signer.')
        if not fields.get('store password') or not fields.get('key password'):
            raise ValueError('Signing bundle has incomplete credentials.')
        return archive.read('pocket-steward-development.jks'), fields


def captured(*args):
    # Never echo commands/environment or private signing-tool diagnostics.
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode:
        raise ValueError('APK verification command failed.')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--signing-bundle', type=pathlib.Path, required=True)
    parser.add_argument('--sdk', type=pathlib.Path, required=True)
    parser.add_argument('--java-home', type=pathlib.Path, required=True)
    parser.add_argument('--build-tools-version', default='36.1.0')
    parser.add_argument('--gradle-init', type=pathlib.Path)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--log', type=pathlib.Path, required=True)
    args = parser.parse_args()
    try:
        bundle = args.signing_bundle.resolve()
        if bundle.is_relative_to(ROOT):
            raise ValueError('Keep the private signing bundle outside the source checkout.')
        key_bytes, fields = read_bundle(bundle)
        build_tools = args.sdk.resolve() / 'build-tools' / args.build_tools_version
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        version_code = re.search(r'\bversionCode\s*=\s*(\d+)', gradle)[1]
        version_name = re.search(r'\bversionName\s*=\s*"([^"]+)"', gradle)[1]
        with tempfile.TemporaryDirectory(prefix='pocket-steward-signing-') as private:
            key = pathlib.Path(private) / 'key.jks'
            key.write_bytes(key_bytes)
            key.chmod(0o600)
            env = os.environ.copy()
            env.update({
                'JAVA_HOME': str(args.java_home.resolve()),
                'ANDROID_HOME': str(args.sdk.resolve()),
                'ANDROID_SDK_ROOT': str(args.sdk.resolve()),
                'POCKET_STEWARD_KEYSTORE_PATH': str(key),
                'POCKET_STEWARD_KEYSTORE_PASSWORD': fields['store password'],
                'POCKET_STEWARD_KEY_ALIAS': fields['alias'],
                'POCKET_STEWARD_KEY_PASSWORD': fields['key password'],
            })
            command = ['./gradlew']
            if args.gradle_init:
                command += ['-I', str(args.gradle_init.resolve())]
            command += ['assembleRelease', '--no-daemon']
            args.log.parent.mkdir(parents=True, exist_ok=True)
            descriptor = os.open(args.log, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            os.chmod(args.log, 0o600)
            with os.fdopen(descriptor, 'w') as log:
                result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            if result.returncode:
                raise ValueError('Release build failed; inspect the private build log.')
        apk = ROOT / 'app/build/outputs/apk/release/app-release.apk'
        cert = captured(str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk))
        match = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', cert)
        if match is None or match[1] != CANONICAL_SIGNER:
            raise ValueError('APK certificate differs from the installed-app signer.')
        badging = captured(str(build_tools / 'aapt'), 'dump', 'badging', str(apk))
        if f"versionCode='{version_code}'" not in badging or f"versionName='{version_name}'" not in badging:
            raise ValueError('APK version differs from source.')
        if "native-code: 'arm64-v8a'" not in badging:
            raise ValueError('Release does not have the expected phone ABI.')
        if 'android.permission.INTERNET' in captured(str(build_tools / 'aapt'), 'dump', 'permissions', str(apk)):
            raise ValueError('Offline release unexpectedly requests INTERNET.')
        output = args.output.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=output.parent, prefix='pocket-verified-') as staging:
            staged = pathlib.Path(staging) / 'release.apk'
            shutil.copy2(apk, staged)
            os.replace(staged, output)
        with output.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        print(json.dumps({'apk': str(output), 'versionName': version_name, 'versionCode': int(version_code),
                          'apkSHA256': digest, 'signerSHA256': CANONICAL_SIGNER, 'internetPermission': False}))
    except (ValueError, KeyError, IndexError, OSError, zipfile.BadZipFile):
        # A malformed credential field/path must never appear in diagnostics.
        parser.exit(1, 'Signing/build verification failed. Check bundle format, SDK/JDK paths and the private log. No APK was published by this command.\n')


if __name__ == '__main__':
    main()
