import sys

with open('app-host/src/main/res/values/strings.xml', 'r', encoding='utf-8') as f:
    text = f.read()

target = '<string name="settings_history_desc">Keep a local history of recently used files. Files are never uploaded.</string>'
replacement = target + '\n    <string name="settings_remember_position">Remember Reading Position</string>\n    <string name="settings_remember_position_desc">Automatically restores your last viewed page when reopening a document.</string>'
text = text.replace(target, replacement)

with open('app-host/src/main/res/values/strings.xml', 'w', encoding='utf-8', newline='\n') as f:
    f.write(text)
