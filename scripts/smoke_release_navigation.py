"""Check real minified navigation restoration on an explicitly selected owned AVD.

Requires a verified artifact receipt, the installed original APK for restoration,
and explicit adb/aapt paths. Does not clear app data or change recipient grants.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('adb', 'aapt', 'serial', 'expected-avd', 'apk', 'artifact-receipt', 'restore-apk', 'report-dir'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--server-port', type=int, required=True)
    args = parser.parse_args()
    out = Path(args.report_dir).resolve()
    out.mkdir(parents=True, exist_ok=False)
    artifact = json.loads(Path(args.artifact_receipt).read_text(encoding='utf-8-sig'))
    digest = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
    expected = digest(args.apk)
    previous = digest(args.restore_apk)
    assert artifact['artifacts']['apk']['sha256'] == expected and artifact['minified']
    assert artifact['certificate_sha256'] and not artifact['debuggable']
    command = [args.adb, '-P', str(args.server_port), '-s', args.serial]
    checks = []
    settings = {}

    def adb(*values):
        result = subprocess.run(command + list(values), capture_output=True, timeout=90)
        assert result.returncode == 0, 'ADB operation failed: ' + values[0]
        return result.stdout.decode('utf-8', errors='replace').strip()

    def installed_digest():
        path = adb('shell', 'pm', 'path', 'com.pdfchemy.app').removeprefix('package:')
        assert path and '\n' not in path
        return adb('shell', 'sha256sum', shlex.quote(path)).split()[0]

    for apk in (args.apk, args.restore_apk):
        badging = subprocess.check_output([args.aapt, 'dump', 'badging', apk], text=True)
        assert "package: name='com.pdfchemy.app'" in badging
        assert 'application-debuggable' not in badging and 'application-testOnly' not in badging
    assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name') == args.expected_avd
    assert args.expected_avd and adb('shell', 'cmd', 'connectivity', 'airplane-mode') == 'enabled'
    assert installed_digest() == previous, 'Installed APK does not match the supplied restoration artifact'
    settings['font_scale'] = adb('shell', 'settings', 'get', 'system', 'font_scale')
    remote_xml = '/sdcard/pdfchemy-release-navigation-smoke.xml'

    def snapshot(name):
        response = adb('shell', 'uiautomator', 'dump', remote_xml)
        if 'dumped to' not in response:
            return ET.Element('pending')
        xml = adb('shell', 'cat', remote_xml)
        (out / (name + '.xml')).write_text(xml, encoding='utf-8')
        return ET.fromstring(xml)

    def find(tree, text):
        return [node for node in tree.iter('node') if node.get('text') == text]

    def wait(name, predicate):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            tree = snapshot(name)
            if predicate(tree):
                return tree
        raise AssertionError('Timed out at ' + name)

    def bounds(node):
        return tuple(map(int, re.findall(r'\d+', node.attrib['bounds'])))

    def click(tree, text):
        nodes = find(tree, text)
        assert len(nodes) == 1, 'Expected one visible ' + text
        x1, y1, x2, y2 = bounds(nodes[0])
        adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))

    def scroll(tree, text, name):
        for index in range(10):
            if find(tree, text):
                return tree
            _, _, width, height = bounds(next(tree.iter('node')))
            adb('shell', 'input', 'swipe', str(width // 2), str(height * 3 // 4),
                str(width // 2), str(height // 3), '300')
            tree = snapshot(name + '-' + str(index))
        raise AssertionError('Route does not expose ' + text)

    def activity(name):
        state = adb('shell', 'dumpsys', 'activity', 'com.pdfchemy.app')
        (out / (name + '-activity.txt')).write_text(state, encoding='utf-8')
        identities = re.findall(r'Local Activity (?:0x)?([0-9a-f]+) State:', state)
        return identities[-1] if identities else None

    def recreate(name, scale, expected_text):
        before = activity(name + '-before')
        assert before
        adb('shell', 'settings', 'put', 'system', 'font_scale', scale)
        last = None
        consecutive = 0

        def settled(tree):
            nonlocal last, consecutive
            if activity(name + '-after') in (None, before):
                return False
            state = [(n.get('text'), n.get('content-desc'), n.get('bounds')) for n in tree.iter('node')]
            consecutive = consecutive + 1 if state and state == last else 0
            last = state
            return consecutive >= 1

        tree = wait(name, settled)
        assert not find(tree, 'Open PDF in Reader'), name + ' restored Home instead of the active route'
        tree = scroll(tree, expected_text, name + '-route')
        checks.append(dict(name=name, passed=True, old_activity=before, new_activity=activity(name + '-confirmed')))
        return tree

    try:
        assert 'Success' in adb('install', '--no-streaming', '-r', args.apk)
        assert installed_digest() == expected
        adb('logcat', '-c')
        adb('shell', 'input', 'keyevent', '224')
        adb('shell', 'wm', 'dismiss-keyguard')
        adb('shell', 'am', 'force-stop', 'com.pdfchemy.app')
        adb('shell', 'am', 'start', '-W', '-f', '0x10008000', '-a', 'android.intent.action.MAIN',
            '-c', 'android.intent.category.LAUNCHER', '-n', 'com.pdfchemy.app/.MainActivity')
        tree = wait('home', lambda tree: bool(find(tree, 'Open PDF in Reader')))
        tree = scroll(tree, 'Create', 'home-scroll')
        click(tree, 'Create')
        tree = wait('create', lambda tree: not find(tree, 'Open PDF in Reader') and bool(list(tree.iter('node'))))
        tree = scroll(tree, 'PDF to PowerPoint', 'create-scroll')
        scale = '1.1' if settings['font_scale'] != '1.1' else '1.2'
        tree = recreate('category-configuration-restoration', scale, 'PDF to PowerPoint')
        click(tree, 'PDF to PowerPoint')
        wait('office', lambda tree: bool(find(tree, 'Choose PDF Document')))
        recreate('office-configuration-restoration', '1.0', 'Choose PDF Document')
        crash = adb('logcat', '-d', '-b', 'crash')
        (out / 'crash.log').write_text(crash, encoding='utf-8')
        assert not [line for line in crash.splitlines() if line.strip() and not line.startswith('--------- beginning of')]
        receipt = dict(status='SIGNED_MINIFIED_NAVIGATION_PASS', source_sha=artifact['source_sha'],
                       apk_sha256=expected, driver_sha256=digest(__file__), checks=checks)
        (out / 'receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
        print(json.dumps(receipt))
    except BaseException as error:
        (out / 'failure.json').write_text(json.dumps(dict(source_sha=artifact['source_sha'], apk_sha256=expected,
            driver_sha256=digest(__file__), type=type(error).__name__, message=str(error), checks=checks), indent=2), encoding='utf-8')
        raise
    finally:
        (out / 'logcat.txt').write_text(adb('logcat', '-d'), encoding='utf-8')
        assert 'Success' in adb('install', '--no-streaming', '-r', args.restore_apk)
        assert installed_digest() == previous
        adb('shell', 'settings', 'put', 'system', 'font_scale', settings['font_scale'])
        (out / 'restoration.json').write_text(json.dumps(dict(restored=True, apk_sha256=previous, settings=settings)), encoding='utf-8')


if __name__ == '__main__':
    main()
