package com.pdfchemy.desktop.jail

import java.io.File
import java.net.Socket
import java.net.InetSocketAddress

/**
 * A mock worker used by tests to attempt adversarial actions against the host OS.
 * It reads the first command-line argument to determine the attack payload.
 */
fun main(args: Array<String>) {
    val attack = args.firstOrNull() ?: "none"
    
    try {
        val result = mutableMapOf<String, Any>()
        
        when (attack) {
            "testNetworkSocket" -> {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress("8.8.8.8", 53), 2000)
                    socket.close()
                    result["success"] = true
                } catch (e: Exception) {
                    result["success"] = false
                    result["error"] = e.message ?: "Unknown"
                }
            }
            "testWriteHostFile" -> {
                try {
                    // Attempt to write a file in the parent project directory
                    val hostFile = File("../../jail_attack_test.txt")
                    hostFile.writeText("adversarial payload")
                    if (hostFile.exists()) {
                        result["success"] = true
                        hostFile.delete()
                    } else {
                        result["success"] = false
                    }
                } catch (e: Exception) {
                    result["success"] = false
                    result["error"] = e.message ?: "Unknown"
                }
            }
            "testCapabilities" -> {
                try {
                    result["success"] = true
                    result["envKeys"] = System.getenv().keys.toList()
                    result["hostCwd"] = System.getProperty("user.dir")
                    
                    val sysProps = mutableMapOf<String, String>()
                    System.getProperties().forEach { k, v ->
                        sysProps[k.toString()] = v.toString()
                    }
                    result["systemProperties"] = sysProps
                    
                    val procFd = File("/proc/self/fd")
                    if (procFd.exists() && procFd.isDirectory) {
                        result["openHandles"] = procFd.listFiles()?.size ?: -1
                    } else {
                        result["openHandles"] = -1
                    }
                } catch (e: Exception) {
                    result["success"] = false
                    result["error"] = e.message ?: "Unknown"
                }
            }
            "spawnSubprocess" -> {
                try {
                    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
                    val pb = if (isWindows) {
                        ProcessBuilder("cmd.exe", "/c", "echo hacked")
                    } else {
                        ProcessBuilder("echo", "hacked")
                    }
                    val process = pb.start()
                    process.waitFor()
                    result["success"] = true
                } catch (e: Exception) {
                    result["success"] = false
                    result["error"] = e.message ?: "Unknown"
                }
            }
            "spawnChildAndSleep" -> {
                try {
                    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
                    val pb = if (isWindows) {
                        ProcessBuilder("cmd.exe", "/c", "ping 127.0.0.1 -n 10 > nul")
                    } else {
                        ProcessBuilder("sleep", "10")
                    }
                    val childProcess = pb.start()
                    result["success"] = true
                    result["childPid"] = childProcess.pid()
                } catch (e: Exception) {
                    result["success"] = false
                    result["error"] = e.message ?: "Unknown"
                }
            }
            "testWmiBreakaway" -> {
                try {
                    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
                    if (isWindows) {
                        // WMI Win32_Process.Create breakaway attempt
                        val pb = ProcessBuilder("powershell.exe", "-Command", "Invoke-WmiMethod -Class Win32_Process -Name Create -ArgumentList 'cmd.exe /c echo hacked'")
                        val process = pb.start()
                        process.waitFor()
                        result["success"] = true
                    } else {
                        result["success"] = false
                    }
                } catch (e: Exception) {
                    result["success"] = false
                    result["error"] = e.message ?: "Unknown"
                }
            }
            "crashMidStream" -> {
                System.out.write(byteArrayOf(0x4A, 0x41))
                System.out.flush()
                kotlin.system.exitProcess(1)
            }
            "hangIndefinitely" -> {
                Thread.sleep(Long.MAX_VALUE)
            }
            "testEnvironmentPoisoning" -> {
                // Flood stderr with ANSI codes and NUL bytes
                System.err.print("\u001B[31mError message\u001B[0m\n")
                System.err.print("Some valid text\u0000with nulls\u0007\n")
                System.err.print("\r\r\n\nDouble returns\n")
                System.err.flush()
                kotlin.system.exitProcess(42)
            }
            "testIpcIdentityConfusion" -> {
                // Send invalid signed chunks to confuse the host
                val secret = System.getenv("ARKHAM_SECRET") ?: ""
                val sessionId = System.getenv("ARKHAM_SESSION") ?: ""
                val fakeJobId = "invalid-job-id"
                
                // Bad signature
                val badSigResp = JailResponse(status = "SUCCESS", type = "SINGLE", jobId = fakeJobId, payload = "bad")
                JailIpc.writeMessage(System.out, JailIpc.gson.toJson(badSigResp), null, 0L)
                
                // Valid signature but wrong job ID
                val validSigButWrongJob = JailResponse(status = "SUCCESS", type = "SINGLE", jobId = fakeJobId)
                val signedWrongJob = validSigButWrongJob.copy(
                    signature = JailCrypto.computeSignature(secret, sessionId, fakeJobId, "worker", "res", "SINGLE", 0, 0L, 0L, "", validSigButWrongJob.nonce, "", "SUCCESS", emptyMap())
                )
                JailIpc.writeMessage(System.out, JailIpc.gson.toJson(signedWrongJob), null, 0L)
                
                // Keep sending gibberish to test parser robustness against bad identity
                Thread.sleep(1000)
            }
            "slowResponse" -> {
                // Send START, then slowly send chunks
                val secret = System.getenv("ARKHAM_SECRET") ?: ""
                val sessionId = System.getenv("ARKHAM_SESSION") ?: ""
                val jobId = "slow-job"
                
                val startReq = JailResponse(status = "SUCCESS", type = "START", jobId = jobId, totalSize = 1024L * 1024L)
                val startSigned = startReq.copy(
                    signature = JailCrypto.computeSignature(secret, sessionId, jobId, "worker", "res", "START", 0, 1024L * 1024L, 0L, "", startReq.nonce, "", "SUCCESS", emptyMap())
                )
                JailIpc.writeMessage(System.out, JailIpc.gson.toJson(startSigned), null, 0L)
                
                for (i in 0 until 10) {
                    Thread.sleep(500) // Sleep to allow host to cancel
                    val chunkBytes = ByteArray(1024 * 102) { 0 }
                    val payloadHash = JailCrypto.computePayloadHash(chunkBytes)
                    val chunkReq = JailResponse(status = "SUCCESS", type = "CHUNK", jobId = jobId, sequence = i, totalSize = 1024L * 1024L, payloadHash = payloadHash)
                    val chunkSigned = chunkReq.copy(
                        signature = JailCrypto.computeSignature(secret, sessionId, jobId, "worker", "res", "CHUNK", i, 1024L * 1024L, chunkBytes.size.toLong(), payloadHash, chunkReq.nonce, "", "SUCCESS", emptyMap())
                    )
                    JailIpc.writeMessageBytes(System.out, JailIpc.gson.toJson(chunkSigned), chunkBytes)
                }
            }
            "omnibusAttack" -> {
                // The ultimate get-everything attack. Spawns threads doing EVERYTHING.
                
                val threads = mutableListOf<Thread>()
                
                // Attack 1: Spawn a subprocess and try to survive
                threads.add(Thread {
                    try {
                        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
                        val pb = if (isWindows) {
                            ProcessBuilder("cmd.exe", "/c", "ping 127.0.0.1 -n 10 > nul")
                        } else {
                            ProcessBuilder("sleep", "10")
                        }
                        pb.start().waitFor()
                    } catch (e: Exception) {}
                })
                
                // Attack 2: Flood stderr
                threads.add(Thread {
                    while (true) {
                        try {
                            System.err.println("NONSENSE ERROR NOISE \u0000 \u001B[31m HAHA")
                            Thread.sleep(10)
                        } catch (e: Exception) {}
                    }
                })
                
                // Attack 3: Flood stdout with invalid IPC data
                threads.add(Thread {
                    while (true) {
                        try {
                            System.out.write(ByteArray(1024) { 42 })
                            System.out.flush()
                            Thread.sleep(50)
                        } catch (e: Exception) {}
                    }
                })
                
                // Attack 4: Try to eat all memory
                threads.add(Thread {
                    val arrays = mutableListOf<ByteArray>()
                    while (true) {
                        try {
                            arrays.add(ByteArray(1024 * 1024))
                            Thread.sleep(10)
                        } catch (e: OutOfMemoryError) {
                            arrays.clear()
                        }
                    }
                })
                
                // Start all attacks
                threads.forEach { it.isDaemon = true; it.start() }
                
                // Hang the main thread to ensure the host has to time out or kill based on framing errors
                Thread.sleep(Long.MAX_VALUE)
            }
            else -> {
                result["success"] = false
                result["error"] = "Unknown attack vector"
            }
        }
        
        val payload = JailIpc.gson.toJson(result)
        val response = JailResponse(status = "SUCCESS", payload = payload)
        
        val secret = System.getenv("ARKHAM_SECRET") ?: ""
        val sessionId = System.getenv("ARKHAM_SESSION") ?: ""
        response.sendChunked(System.out, null, null, secret, sessionId)
        
    } catch (e: Exception) {
        val errResponse = JailResponse(status = "ERROR", errorMessage = e.message ?: "Unknown error")
        val secret = System.getenv("ARKHAM_SECRET") ?: ""
        val sessionId = System.getenv("ARKHAM_SESSION") ?: ""
        errResponse.sendChunked(System.out, null, null, secret, sessionId)
    }
}
