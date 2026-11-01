"""Reject unpinned or retired JavaScript action runtimes before Android builds."""
from pathlib import Path
import json
import re
import urllib.request

WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/android-release.yml'


def action_refs(workflow):
    refs = set(re.findall(r'^\s*(?:-\s*)?uses:\s*([^\s#]+)', workflow, re.M))
    if not refs:
        raise ValueError('No action references found')
    for ref in refs:
        if not re.fullmatch(r'[\w.-]+/[\w./-]+@[0-9a-f]{40}', ref):
            raise ValueError('Action must use an immutable commit: ' + ref)
    return sorted(refs)


def runtime(manifest):
    value = re.search(r'^\s+using:\s*[\'"]?(node\d+|composite|docker)[\'"]?\s*(?:#.*)?$', manifest, re.M)
    if not value or value.group(1) != 'node24':
        raise ValueError('Required action runtime is node24')
    return value.group(1)


def check(workflow, fetch):
    results = []
    for ref in action_refs(workflow):
        name, sha = ref.split('@')
        owner, repository, *subpath = name.split('/')
        url = f'https://raw.githubusercontent.com/{owner}/{repository}/{sha}/' + '/'.join(subpath + ['action.yml'])
        results.append(dict(action=ref, runtime=runtime(fetch(url))))
    return results


def fetch(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        raw = response.read(65_537)
    if len(raw) > 65_536:
        raise ValueError('Action manifest exceeds inspection limit')
    return raw.decode('utf-8')


if __name__ == '__main__':
    print(json.dumps(check(WORKFLOW.read_text(encoding='utf-8'), fetch), indent=2))
