import re
import sys

def process_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    # The block we are looking for is:
    #         val result = withTimeoutOrNull(...) {
    #             channel.receive()
    #         }
    #         [maybe some stuff]
    #         if (result == null && workerPid != -1) {
    #             [AppLogger and Process.killProcess]
    #             [optional return]
    #         }
    
    # Let's match it exactly.
    pattern = re.compile(
        r'(?P<indent>[ \t]*)val result = withTimeoutOrNull\((?P<timeout>HARD_TIMEOUT_MS|180_000L)\)\s*\{\s*channel\.receive\(\)\s*\}\s*'
        r'(?P<cleanup>.*?)'
        r'if\s*\(result == null && workerPid != -1\)\s*\{\s*'
        r'(?P<log>.*?)'
        r'(?P<indent2>[ \t]*)Process\.killProcess\(workerPid\)\s*'
        r'(?P<ret>return@withContext [^\n]*)?'
        r'\s*\}\s*', re.MULTILINE | re.DOTALL
    )
    
    def repl(m):
        indent = m.group('indent')
        timeout = m.group('timeout')
        cleanup = m.group('cleanup')
        log = m.group('log').strip()
        if log:
            log = f"\n{indent}        " + log.replace("\n", f"\n{indent}        ") + "\n"
        else:
            log = "\n"
        ret = m.group('ret')
        
        # Determine the name of the operation based on the log or return statement
        if ret:
            ret_code = f"\n{indent}if (result == null) {{\n{indent}    {ret.replace('timed out', 'timed out or cancelled')}\n{indent}}}\n"
        else:
            ret_code = ""

        return (
            f"{indent}var timeoutOrCancel = true\n"
            f"{indent}val result = try {{\n"
            f"{indent}    val res = withTimeoutOrNull({timeout}) {{\n"
            f"{indent}        channel.receive()\n"
            f"{indent}    }}\n"
            f"{indent}    if (res != null) {{\n"
            f"{indent}        timeoutOrCancel = false\n"
            f"{indent}    }}\n"
            f"{indent}    res\n"
            f"{indent}}} finally {{\n"
            f"{indent}    try {{\n"
            f"{indent}        context.unbindService(connection)\n"
            f"{indent}    }} catch (e: Exception) {{}}\n"
            f"{indent}\n"
            f"{indent}    if (timeoutOrCancel && workerPid != -1) {{{log}"
            f"{indent}        Process.killProcess(workerPid)\n"
            f"{indent}    }}\n"
            f"{indent}}}\n"
            f"{ret_code}\n"
        )

    new_content = pattern.sub(repl, content)

    with open(path, 'w', encoding='utf-8') as f:
        f.write(new_content)
        
    print(f"Replaced {len(pattern.findall(content))} instances.")

process_file(r'app\src\main\java\com\pdfchemy\app\sandbox\SandboxCoordinator.kt')
