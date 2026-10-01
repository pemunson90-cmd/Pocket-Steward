#!/usr/bin/env python3
"""Publish committed source via GitData and prove the app tree matches locally."""
import argparse
import base64
import json
import pathlib
import re
import subprocess

from publish_release import api

ROOT = pathlib.Path(__file__).resolve().parents[1]


def git(*args, text=True):
    value = subprocess.check_output(['git', *args], cwd=ROOT)
    return value.decode().strip() if text else value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', default='pemunson90-cmd/Pocket-Steward')
    parser.add_argument('--branch', default='upgrade/app-completion')
    parser.add_argument('--baseline', required=True, help='Full original baseline commit SHA')
    parser.add_argument('--message-file', type=pathlib.Path, required=True)
    parser.add_argument('--receipt', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{40}', args.baseline):
        parser.error('Use a full baseline commit SHA.')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
        parser.error('Use owner/repository.')
    if not re.fullmatch(r'[A-Za-z0-9_./-]+', args.branch):
        parser.error('Invalid publication branch.')
    if git('status', '--porcelain', '--untracked-files=no'):
        parser.error('Commit tracked source before publishing.')
    git('rev-parse', '--verify', f'{args.baseline}^{{commit}}')
    parent = api(args.repository, f'git/ref/heads/{args.branch}')['object']['sha']
    base = api(args.repository, f'git/commits/{parent}')['tree']['sha']
    entries = []
    expected = {}
    paths = git('diff', '--name-only', '--no-renames', args.baseline, 'HEAD').splitlines()
    for path in paths:
        leaf = pathlib.PurePosixPath(path).name.lower()
        if leaf.endswith(('.jks', '.keystore', '.apk')) or leaf in {'.env', 'signing-credentials.txt'}:
            parser.error('Refusing to publish private/build artifacts as source.')
        listing = git('ls-tree', 'HEAD', '--', path)
        if not listing:
            entries.append({'path': path, 'mode': '100644', 'type': 'blob', 'sha': None})
            expected[path] = None
            continue
        meta, _ = listing.split('\t', 1)
        mode, kind, sha = meta.split()
        if kind != 'blob' or mode not in {'100644', '100755'}:
            parser.error('Unsupported source entry type.')
        data = git('show', f'HEAD:{path}', text=False)
        entry = {'path': path, 'mode': mode, 'type': 'blob'}
        try:
            content = data.decode('utf-8')
            if '\0' in content:
                raise UnicodeError()
            entry['content'] = content
        except UnicodeError:
            uploaded = api(args.repository, 'git/blobs', {'encoding': 'base64', 'content': base64.b64encode(data).decode()})['sha']
            if uploaded != sha:
                raise ValueError('Binary source blob verification failed.')
            entry['sha'] = uploaded
        expected[path] = sha
        entries.append(entry)
    if not entries:
        parser.error('No source delta from baseline.')
    tree = api(args.repository, 'git/trees', {'base_tree': base, 'tree': entries})['sha']
    inventory = api(args.repository, f'git/trees/{tree}?recursive=1')
    if inventory.get('truncated'):
        raise ValueError('Cannot verify a truncated source tree.')
    remote = {entry['path']: entry['sha'] for entry in inventory['tree']}
    if remote.get('app') != git('rev-parse', 'HEAD:app'):
        raise ValueError('Remote app tree differs from the tested local tree. Branch was not updated.')
    for path, sha in expected.items():
        if remote.get(path) != sha:
            raise ValueError(f'Source verification failed: {path}. Branch was not updated.')
    message = args.message_file.read_text().strip()
    if not message or len(message) > 20_000:
        parser.error('Use a nonempty bounded commit message file.')
    commit = api(args.repository, 'git/commits', {'message': message, 'tree': tree, 'parents': [parent]})['sha']
    # Compare-and-fast-forward: do not overwrite a concurrently advanced branch.
    update = subprocess.run(['gh', 'api', f'repos/{args.repository}/git/refs/heads/{args.branch}',
                             '--method', 'PATCH', '--input', '-'], input=json.dumps({'sha': commit, 'force': False}),
                            text=True, capture_output=True)
    if update.returncode:
        raise RuntimeError('Branch update rejected. Re-read the branch and retry; no force update was attempted.')
    receipt = {'local': git('rev-parse', 'HEAD'), 'remote': commit, 'appTreeSHA1': remote['app'],
               'appTreeVerified': True, 'repository': args.repository, 'branch': args.branch}
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(receipt, indent=2) + '\n')
    print(f'Published verified app tree: {commit}')


if __name__ == '__main__':
    main()
