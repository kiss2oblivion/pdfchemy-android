import re

path = 'app/src/main/java/com/pdfchemy/app/sandbox/SandboxCoordinator.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# auditDocumentThreats
content = content.replace(
    '''                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                try {
                    workerPid = sandbox.workerPid
                    val pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")''',
    '''                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                var pfd: ParcelFileDescriptor? = null
                try {
                    workerPid = sandbox.workerPid
                    pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")'''
).replace(
    '''                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(null)
                }''',
    '''                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(null)
                } finally {
                    try { pfd?.close() } catch (_: Exception) {}
                }'''
)

# sanitizeDocument
content = content.replace(
    '''                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                try {
                    workerPid = sandbox.workerPid
                    val inputPfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                    val outputPfd = context.contentResolver.openFileDescriptor(destUri, "w")''',
    '''                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                var inputPfd: ParcelFileDescriptor? = null
                var outputPfd: ParcelFileDescriptor? = null
                try {
                    workerPid = sandbox.workerPid
                    inputPfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                    outputPfd = context.contentResolver.openFileDescriptor(destUri, "w")'''
).replace(
    '''                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(null)
                }''',
    '''                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(null)
                } finally {
                    try { inputPfd?.close() } catch (_: Exception) {}
                    try { outputPfd?.close() } catch (_: Exception) {}
                }'''
)

# pdfToEpub
content = content.replace(
    '''                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                try {
                    workerPid = sandbox.workerPid
                    val inputPfd = context.contentResolver.openFileDescriptor(sourcePdfUri, "r")
                    val outputPfd = context.contentResolver.openFileDescriptor(destEpubUri, "w")''',
    '''                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                var inputPfd: ParcelFileDescriptor? = null
                var outputPfd: ParcelFileDescriptor? = null
                try {
                    workerPid = sandbox.workerPid
                    inputPfd = context.contentResolver.openFileDescriptor(sourcePdfUri, "r")
                    outputPfd = context.contentResolver.openFileDescriptor(destEpubUri, "w")'''
).replace(
    '''                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(Result.failure(e))
                }''',
    '''                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(Result.failure(e))
                } finally {
                    try { inputPfd?.close() } catch (_: Exception) {}
                    try { outputPfd?.close() } catch (_: Exception) {}
                }'''
)

# epubToPdf
content = content.replace(
    '''                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                try {
                    workerPid = sandbox.workerPid
                    val inputPfd = context.contentResolver.openFileDescriptor(sourceEpubUri, "r")
                    val outputPfd = context.contentResolver.openFileDescriptor(destPdfUri, "w")''',
    '''                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                var inputPfd: ParcelFileDescriptor? = null
                var outputPfd: ParcelFileDescriptor? = null
                try {
                    workerPid = sandbox.workerPid
                    inputPfd = context.contentResolver.openFileDescriptor(sourceEpubUri, "r")
                    outputPfd = context.contentResolver.openFileDescriptor(destPdfUri, "w")'''
).replace(
    '''                            onProgress(progress, 100)
                        }
                    })
                } catch (e: Exception) {
                    channel.trySend(Result.failure(e))
                }''',
    '''                            onProgress(progress, 100)
                        }
                    })
                } catch (e: Exception) {
                    channel.trySend(Result.failure(e))
                } finally {
                    try { inputPfd?.close() } catch (_: Exception) {}
                    try { outputPfd?.close() } catch (_: Exception) {}
                }'''
)

# searchRedactionTargets
content = content.replace(
    '''                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                try {
                    workerPid = sandbox.workerPid
                    val inputPfd = context.contentResolver.openFileDescriptor(pdfUri, "r")''',
    '''                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                var inputPfd: ParcelFileDescriptor? = null
                try {
                    workerPid = sandbox.workerPid
                    inputPfd = context.contentResolver.openFileDescriptor(pdfUri, "r")'''
).replace(
    '''                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(Result.failure(e))
                }''',
    '''                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(Result.failure(e))
                } finally {
                    try { inputPfd?.close() } catch (_: Exception) {}
                }'''
)

# applyRedactions
content = content.replace(
    '''                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                try {
                    workerPid = sandbox.workerPid
                    val inputPfd = context.contentResolver.openFileDescriptor(sourcePdfUri, "r")
                    val outputPfd = context.contentResolver.openFileDescriptor(destPdfUri, "w")''',
    '''                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                var inputPfd: ParcelFileDescriptor? = null
                var outputPfd: ParcelFileDescriptor? = null
                try {
                    workerPid = sandbox.workerPid
                    inputPfd = context.contentResolver.openFileDescriptor(sourcePdfUri, "r")
                    outputPfd = context.contentResolver.openFileDescriptor(destPdfUri, "w")'''
).replace(
    '''                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(Result.failure(e))
                }''',
    '''                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(Result.failure(e))
                } finally {
                    try { inputPfd?.close() } catch (_: Exception) {}
                    try { outputPfd?.close() } catch (_: Exception) {}
                }'''
)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied.")
