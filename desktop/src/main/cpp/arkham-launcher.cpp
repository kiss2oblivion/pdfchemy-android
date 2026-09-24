#include <windows.h>
#include <userenv.h>
#include <sddl.h>
#include <bcrypt.h>
#include <string>
#include <vector>
#include <iostream>
#include <iomanip>
#include <fstream>
#include <aclapi.h>
#include <sddl.h>
#include <userenv.h>

#pragma comment(lib, "Userenv.lib")
#pragma comment(lib, "Bcrypt.lib")
#pragma comment(lib, "Advapi32.lib")
#pragma comment(lib, "User32.lib")

void fail(const std::string& msg) {
    DWORD err = GetLastError();
    std::cerr << "FAIL: " << msg << " (Error " << err << ")" << std::endl;
    ExitProcess(1);
}

std::wstring toWString(const std::string& str) {
    if (str.empty()) return std::wstring();
    int len = MultiByteToWideChar(CP_UTF8, 0, str.c_str(), str.length(), NULL, 0);
    std::wstring ws(len, 0);
    MultiByteToWideChar(CP_UTF8, 0, str.c_str(), str.length(), &ws[0], len);
    return ws;
}

std::string hashFile(HANDLE hFile) {
    BCRYPT_ALG_HANDLE hAlg = NULL;
    BCRYPT_HASH_HANDLE hHash = NULL;
    DWORD cbHashObject = 0, cbResult = 0;
    PBYTE pbHashObject = NULL, pbHash = NULL;
    
    if (BCryptOpenAlgorithmProvider(&hAlg, BCRYPT_SHA256_ALGORITHM, NULL, 0) != 0) fail("BCryptOpenAlgorithmProvider failed");
    BCryptGetProperty(hAlg, BCRYPT_OBJECT_LENGTH, (PBYTE)&cbHashObject, sizeof(DWORD), &cbResult, 0);
    pbHashObject = new BYTE[cbHashObject];
    BCryptGetProperty(hAlg, BCRYPT_HASH_LENGTH, (PBYTE)&cbResult, sizeof(DWORD), &cbResult, 0);
    pbHash = new BYTE[cbResult];
    
    if (BCryptCreateHash(hAlg, &hHash, pbHashObject, cbHashObject, NULL, 0, 0) != 0) fail("BCryptCreateHash failed");
    
    BYTE buffer[8192];
    DWORD bytesRead;
    while (ReadFile(hFile, buffer, sizeof(buffer), &bytesRead, NULL) && bytesRead > 0) {
        BCryptHashData(hHash, buffer, bytesRead, 0);
    }
    
    BCryptFinishHash(hHash, pbHash, cbResult, 0);
    
    std::string hex;
    const char* hexChars = "0123456789abcdef";
    for (DWORD i = 0; i < cbResult; i++) {
        hex += hexChars[(pbHash[i] >> 4) & 0x0F];
        hex += hexChars[pbHash[i] & 0x0F];
    }
    
    BCryptDestroyHash(hHash);
    BCryptCloseAlgorithmProvider(hAlg, 0);
    delete[] pbHashObject;
    delete[] pbHash;
    
    return hex;
}

bool AddAceToWindowStationAndDesktop(PSID sid) {
    HWINSTA hWinsta = GetProcessWindowStation();
    HDESK hDesk = GetThreadDesktop(GetCurrentThreadId());
    
    if (!hWinsta || !hDesk) return false;

    EXPLICIT_ACCESS_W ea[2];
    ZeroMemory(&ea, 2 * sizeof(EXPLICIT_ACCESS_W));
    
    // Grant WINSTA_ALL_ACCESS
    ea[0].grfAccessPermissions = WINSTA_ALL_ACCESS;
    ea[0].grfAccessMode = GRANT_ACCESS;
    ea[0].grfInheritance = NO_INHERITANCE;
    ea[0].Trustee.TrusteeForm = TRUSTEE_IS_SID;
    ea[0].Trustee.TrusteeType = TRUSTEE_IS_WELL_KNOWN_GROUP;
    ea[0].Trustee.ptstrName = (LPWSTR)sid;

    PACL pOldDacl = NULL, pNewDacl = NULL;
    PSECURITY_DESCRIPTOR pSD = NULL;
    if (GetSecurityInfo(hWinsta, SE_WINDOW_OBJECT, DACL_SECURITY_INFORMATION, NULL, NULL, &pOldDacl, NULL, &pSD) != ERROR_SUCCESS) return false;
    if (SetEntriesInAclW(1, &ea[0], pOldDacl, &pNewDacl) != ERROR_SUCCESS) return false;
    DWORD res1 = SetSecurityInfo(hWinsta, SE_WINDOW_OBJECT, DACL_SECURITY_INFORMATION, NULL, NULL, pNewDacl, NULL);
    if (pSD) LocalFree(pSD); 
    if (pNewDacl) LocalFree(pNewDacl);
    if (res1 != ERROR_SUCCESS) return false;

    // Grant DESKTOP_ALL_ACCESS (using GENERIC_ALL)
    ea[1].grfAccessPermissions = GENERIC_ALL;
    ea[1].grfAccessMode = GRANT_ACCESS;
    ea[1].grfInheritance = NO_INHERITANCE;
    ea[1].Trustee.TrusteeForm = TRUSTEE_IS_SID;
    ea[1].Trustee.TrusteeType = TRUSTEE_IS_WELL_KNOWN_GROUP;
    ea[1].Trustee.ptstrName = (LPWSTR)sid;

    pOldDacl = NULL; pNewDacl = NULL; pSD = NULL;
    if (GetSecurityInfo(hDesk, SE_WINDOW_OBJECT, DACL_SECURITY_INFORMATION, NULL, NULL, &pOldDacl, NULL, &pSD) != ERROR_SUCCESS) return false;
    if (SetEntriesInAclW(1, &ea[1], pOldDacl, &pNewDacl) != ERROR_SUCCESS) return false;
    DWORD res2 = SetSecurityInfo(hDesk, SE_WINDOW_OBJECT, DACL_SECURITY_INFORMATION, NULL, NULL, pNewDacl, NULL);
    if (pSD) LocalFree(pSD); 
    if (pNewDacl) LocalFree(pNewDacl);
    if (res2 != ERROR_SUCCESS) return false;

    return true;
}

std::wstring quoteArg(const std::wstring& arg) {
    if (arg.empty()) return L"\"\"";
    if (arg.find_first_of(L" \t\n\v\"") == std::wstring::npos) return arg;
    
    std::wstring quoted = L"\"";
    for (size_t i = 0; i < arg.length(); ++i) {
        size_t numBackslashes = 0;
        while (i < arg.length() && arg[i] == L'\\') {
            ++i;
            ++numBackslashes;
        }
        if (i == arg.length()) {
            quoted.append(numBackslashes * 2, L'\\');
            break;
        } else if (arg[i] == L'"') {
            quoted.append(numBackslashes * 2 + 1, L'\\');
            quoted.push_back(arg[i]);
        } else {
            quoted.append(numBackslashes, L'\\');
            quoted.push_back(arg[i]);
        }
    }
    quoted.push_back(L'"');
    return quoted;
}

int main(int argc, char* argv[]) {
    if (argc >= 2 && std::string(argv[1]) == "--get-sid") {
        PCWSTR appContainerName = L"ArkhamAsylumWorker";
        PSID appContainerSid = NULL;
        HRESULT hr = DeriveAppContainerSidFromAppContainerName(appContainerName, &appContainerSid);
        if (SUCCEEDED(hr)) {
            LPSTR sidString = NULL;
            if (ConvertSidToStringSidA(appContainerSid, &sidString)) {
                std::cout << sidString << std::endl;
                LocalFree(sidString);
            }
            FreeSid(appContainerSid);
            return 0;
        }
        return 1;
    }
    if (argc >= 3 && std::string(argv[1]) == "--inspect") {
        DWORD pid = std::stoul(argv[2]);
        HANDLE hProcess = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE, pid);
        if (!hProcess) {
            std::cout << "{\"error\": \"OpenProcess failed with error " << GetLastError() << " on pid " << pid << "\"}" << std::endl;
            return 1;
        }

        FILETIME creationTime, exitTime, kernelTime, userTime;
        if (!GetProcessTimes(hProcess, &creationTime, &exitTime, &kernelTime, &userTime)) {
            std::cout << "{\"error\": \"GetProcessTimes failed\"}" << std::endl;
            CloseHandle(hProcess);
            return 1;
        }
        uint64_t cTime = (uint64_t(creationTime.dwHighDateTime) << 32) | creationTime.dwLowDateTime;

        BOOL isInJob = FALSE;
        IsProcessInJob(hProcess, NULL, &isInJob);

        HANDLE hToken = NULL;
        std::string appContainerSidStr = "none";
        std::string integrityLevel = "unknown";
        
        if (OpenProcessToken(hProcess, TOKEN_QUERY, &hToken)) {
            DWORD dwLength = 0;
            GetTokenInformation(hToken, TokenAppContainerSid, NULL, 0, &dwLength);
            if (dwLength > 0) {
                PTOKEN_APPCONTAINER_INFORMATION pAppContainerInfo = (PTOKEN_APPCONTAINER_INFORMATION)malloc(dwLength);
                if (GetTokenInformation(hToken, TokenAppContainerSid, pAppContainerInfo, dwLength, &dwLength)) {
                    if (pAppContainerInfo->TokenAppContainer != NULL) {
                        LPSTR sidString = NULL;
                        if (ConvertSidToStringSidA(pAppContainerInfo->TokenAppContainer, &sidString)) {
                            appContainerSidStr = sidString;
                            LocalFree(sidString);
                        }
                    }
                }
                free(pAppContainerInfo);
            }
            
            dwLength = 0;
            GetTokenInformation(hToken, TokenIntegrityLevel, NULL, 0, &dwLength);
            if (dwLength > 0) {
                PTOKEN_MANDATORY_LABEL pTIL = (PTOKEN_MANDATORY_LABEL)malloc(dwLength);
                if (GetTokenInformation(hToken, TokenIntegrityLevel, pTIL, dwLength, &dwLength)) {
                    DWORD dwIntegrityLevel = *GetSidSubAuthority(pTIL->Label.Sid, 
                                            (DWORD)(UCHAR)(*GetSidSubAuthorityCount(pTIL->Label.Sid)-1));
                    if (dwIntegrityLevel == SECURITY_MANDATORY_LOW_RID) integrityLevel = "Low";
                    else if (dwIntegrityLevel == SECURITY_MANDATORY_MEDIUM_RID) integrityLevel = "Medium";
                    else if (dwIntegrityLevel == SECURITY_MANDATORY_HIGH_RID) integrityLevel = "High";
                    else if (dwIntegrityLevel == SECURITY_MANDATORY_SYSTEM_RID) integrityLevel = "System";
                    else integrityLevel = std::to_string(dwIntegrityLevel);
                }
                free(pTIL);
            }
            CloseHandle(hToken);
        }

        std::cout << "{\n"
                  << "  \"protocolVersion\": 1,\n"
                  << "  \"pid\": " << pid << ",\n"
                  << "  \"processCreateTime\": " << cTime << ",\n"
                  << "  \"appContainerSid\": \"" << appContainerSidStr << "\",\n"
                  << "  \"integrityLevel\": \"" << integrityLevel << "\",\n"
                  << "  \"isInJob\": " << (isInJob ? "true" : "false") << "\n"
                  << "}" << std::endl;

        CloseHandle(hProcess);
        return 0;
    }

    if (argc < 7) {
        std::cerr << "Usage: arkham-launcher.exe <expected_hash> <max_ram_bytes> <max_cpu_percent> <jar_path> <java_bin> <args...>" << std::endl;
        return 1;
    }

    std::string expectedHash = argv[1];
    SIZE_T maxRamBytes = std::stoull(argv[2]);
    DWORD maxCpuPercent = std::stoul(argv[3]);
    std::string jarPath = argv[4];
    
    std::wstring wJavaBin = toWString(argv[5]);
    std::wstring cmdLine;
    for (int i = 5; i < argc; ++i) {
        if (i > 5) cmdLine += L" ";
        cmdLine += quoteArg(toWString(argv[i]));
    }
    HANDLE hJar = CreateFileW(toWString(jarPath).c_str(), GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hJar == INVALID_HANDLE_VALUE) fail("Failed to open jar file");
    
    std::string actualHash = hashFile(hJar);
    if (actualHash != expectedHash) {
        fail("Artifact hash mismatch. Expected: " + expectedHash + " Actual: " + actualHash + " Path: " + jarPath);
    }
    
    // We intentionally leave hJar open so that the file cannot be replaced via a TOCTOU race before the JVM loads it.
    // The JVM will be able to read it because FILE_SHARE_READ is set.

    // 2. Create AppContainer Profile
    PCWSTR appContainerName = L"ArkhamAsylumWorker";
    PCWSTR appContainerDesc = L"PDFchemy Arkham Asylum Worker";
    PSID appContainerSid = NULL;
    HRESULT hr = CreateAppContainerProfile(appContainerName, appContainerName, appContainerDesc, NULL, 0, &appContainerSid);
    if (hr == HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS)) {
        hr = DeriveAppContainerSidFromAppContainerName(appContainerName, &appContainerSid);
    }
    if (FAILED(hr)) {
        fail("CreateAppContainerProfile / DeriveAppContainerSidFromAppContainerName failed");
    }

    // Grant access to Window Station and Desktop so USER32.dll can initialize successfully
    if (!AddAceToWindowStationAndDesktop(appContainerSid)) {
        fail("AddAceToWindowStationAndDesktop failed");
    }

    // 4. Setup STARTUPINFOEX for AppContainer and Handle Allowlist
    STARTUPINFOEXW siex = { 0 };
    siex.StartupInfo.cb = sizeof(STARTUPINFOEXW);
    siex.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    
    SIZE_T attributeListSize = 0;
    InitializeProcThreadAttributeList(NULL, 3, 0, &attributeListSize);
    siex.lpAttributeList = (LPPROC_THREAD_ATTRIBUTE_LIST)HeapAlloc(GetProcessHeap(), 0, attributeListSize);
    if (!InitializeProcThreadAttributeList(siex.lpAttributeList, 3, 0, &attributeListSize)) {
        fail("InitializeProcThreadAttributeList failed");
    }

    SECURITY_CAPABILITIES secCaps = { 0 };
    secCaps.AppContainerSid = appContainerSid;
    if (!UpdateProcThreadAttribute(siex.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, &secCaps, sizeof(secCaps), NULL, NULL)) {
        fail("UpdateProcThreadAttribute SecurityCapabilities failed");
    }

    DWORD lpacPolicy = PROCESS_CREATION_ALL_APPLICATION_PACKAGES_OPT_OUT;
    if (!UpdateProcThreadAttribute(siex.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_ALL_APPLICATION_PACKAGES_POLICY, &lpacPolicy, sizeof(lpacPolicy), NULL, NULL)) {
        fail("UpdateProcThreadAttribute LPAC failed");
    }

    HANDLE hStdInRaw = GetStdHandle(STD_INPUT_HANDLE);
    HANDLE hStdOutRaw = GetStdHandle(STD_OUTPUT_HANDLE);
    HANDLE hStdErrRaw = GetStdHandle(STD_ERROR_HANDLE);

    // We must create our own pipes with a security descriptor that grants ALL_APPLICATION_PACKAGES (AC) access.
    // The handles inherited from Java do not have WRITE_DAC, so we cannot modify them.
    SECURITY_ATTRIBUTES sa = {0};
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;
    PSECURITY_DESCRIPTOR pSD = NULL;
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(L"D:(A;OICI;GA;;;SY)(A;OICI;GA;;;BA)(A;OICI;GA;;;S-1-15-2-2)", SDDL_REVISION_1, &pSD, NULL)) {
        fail("ConvertStringSecurityDescriptorToSecurityDescriptorW failed");
    }
    sa.lpSecurityDescriptor = pSD;

    HANDLE childInRead, childInWrite;
    HANDLE childOutRead, childOutWrite;
    HANDLE childErrRead, childErrWrite;

    if (!CreatePipe(&childInRead, &childInWrite, &sa, 0)) fail("CreatePipe stdin failed");
    if (!CreatePipe(&childOutRead, &childOutWrite, &sa, 0)) fail("CreatePipe stdout failed");
    if (!CreatePipe(&childErrRead, &childErrWrite, &sa, 0)) fail("CreatePipe stderr failed");

    // Ensure the ends we keep are not inherited by the child
    SetHandleInformation(childInWrite, HANDLE_FLAG_INHERIT, 0);
    SetHandleInformation(childOutRead, HANDLE_FLAG_INHERIT, 0);
    SetHandleInformation(childErrRead, HANDLE_FLAG_INHERIT, 0);
    
    HANDLE hJob = CreateJobObjectW(NULL, NULL);
    if (!hJob) fail("CreateJobObjectW failed");

    JOBOBJECT_EXTENDED_LIMIT_INFORMATION jeli = {0};
    jeli.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (maxRamBytes > 0) {
        jeli.BasicLimitInformation.LimitFlags |= JOB_OBJECT_LIMIT_JOB_MEMORY;
        jeli.JobMemoryLimit = maxRamBytes;
    }
    if (!SetInformationJobObject(hJob, JobObjectExtendedLimitInformation, &jeli, sizeof(jeli))) {
        fail("SetInformationJobObject extended limit failed");
    }
    
    if (maxCpuPercent > 0 && maxCpuPercent <= 100) {
        JOBOBJECT_CPU_RATE_CONTROL_INFORMATION jcpu = {0};
        jcpu.ControlFlags = JOB_OBJECT_CPU_RATE_CONTROL_ENABLE | JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP;
        jcpu.CpuRate = maxCpuPercent * 100; // out of 10,000
        if (!SetInformationJobObject(hJob, JobObjectCpuRateControlInformation, &jcpu, sizeof(jcpu))) {
            fail("SetInformationJobObject cpu rate failed");
        }
    }

    std::vector<HANDLE> handleList;
    handleList.push_back(childInRead);
    handleList.push_back(childOutWrite);
    handleList.push_back(childErrWrite);
    
    // Add parent's raw handles to the handle list to test if JVM needs them to avoid Exit 6
    if (hStdInRaw && hStdInRaw != INVALID_HANDLE_VALUE) handleList.push_back(hStdInRaw);
    if (hStdOutRaw && hStdOutRaw != INVALID_HANDLE_VALUE) handleList.push_back(hStdOutRaw);
    if (hStdErrRaw && hStdErrRaw != INVALID_HANDLE_VALUE) handleList.push_back(hStdErrRaw);
    
    if (!UpdateProcThreadAttribute(siex.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handleList.data(), handleList.size() * sizeof(HANDLE), NULL, NULL)) {
        fail("UpdateProcThreadAttribute HandleList failed");
    }

    siex.StartupInfo.hStdInput = childInRead;
    siex.StartupInfo.hStdOutput = childOutWrite;
    siex.StartupInfo.hStdError = childErrWrite;

    // 5. Create Process Suspended
    std::string ncmdLine(cmdLine.begin(), cmdLine.end());
    std::cerr << "DEBUG_CMDLINE: " << ncmdLine << std::endl;

    PROCESS_INFORMATION pi = {0};
    if (!CreateProcessW(
        wJavaBin.c_str(),
        &cmdLine[0],
        NULL,
        NULL,
        TRUE, // Inherit handles (important for pipes)
        EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_NO_WINDOW,
        NULL,
        NULL,
        &siex.StartupInfo,
        &pi
    )) {
        fail("CreateProcessW failed");
    }

    // Print the worker PID to stderr so the host can find it
    std::cerr << "WORKER_PID:" << pi.dwProcessId << std::endl;

    // 6. Assign to Job Object
    if (!AssignProcessToJobObject(hJob, pi.hProcess)) {
        TerminateProcess(pi.hProcess, 1);
        fail("AssignProcessToJobObject failed");
    }

    // 7. Verify Process in Job
    BOOL isProcessInJob = FALSE;
    if (!IsProcessInJob(pi.hProcess, hJob, &isProcessInJob) || !isProcessInJob) {
        TerminateProcess(pi.hProcess, 1);
        fail("IsProcessInJob verification failed");
    }

    // 8. Close the child ends of the pipes in the parent so we don't hold them open
    CloseHandle(childInRead);
    CloseHandle(childOutWrite);
    CloseHandle(childErrWrite);

    // 9. Resume Thread
    ResumeThread(pi.hThread);

    // 10. Start proxy threads for stdout and stderr (stdin is rarely used, but we proxy it in the main thread)
    struct ProxyCtx {
        HANDLE hRead;
        HANDLE hWrite;
    };

    auto proxyLoop = [](LPVOID param) -> DWORD {
        ProxyCtx* ctx = (ProxyCtx*)param;
        char buf[4096];
        DWORD bytesRead, bytesWritten;
        while (ReadFile(ctx->hRead, buf, sizeof(buf), &bytesRead, NULL) && bytesRead > 0) {
            if (!WriteFile(ctx->hWrite, buf, bytesRead, &bytesWritten, NULL)) {
                break;
            }
        }
        return 0;
    };

    ProxyCtx outCtx = { childOutRead, hStdOutRaw };
    ProxyCtx errCtx = { childErrRead, hStdErrRaw };

    HANDLE hOutThread = CreateThread(NULL, 0, proxyLoop, &outCtx, 0, NULL);
    HANDLE hErrThread = CreateThread(NULL, 0, proxyLoop, &errCtx, 0, NULL);

    ProxyCtx inCtx = { hStdInRaw, childInWrite };
    HANDLE hInThread = CreateThread(NULL, 0, proxyLoop, &inCtx, 0, NULL);

    // Wait for the worker to finish
    WaitForSingleObject(pi.hProcess, INFINITE);
    
    // Do not wait for hInThread because it may be blocked on ReadFile(stdin) indefinitely.
    if (hOutThread) WaitForSingleObject(hOutThread, INFINITE);
    if (hErrThread) WaitForSingleObject(hErrThread, INFINITE);

    DWORD exitCode = 0;
    GetExitCodeProcess(pi.hProcess, &exitCode);
    
    if (hOutThread) CloseHandle(hOutThread);
    if (hErrThread) CloseHandle(hErrThread);
    CloseHandle(childOutRead);
    CloseHandle(childErrRead);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    CloseHandle(hJob);
    CloseHandle(hJar);
    FreeSid(appContainerSid);
    DeleteProcThreadAttributeList(siex.lpAttributeList);
    HeapFree(GetProcessHeap(), 0, siex.lpAttributeList);

    return exitCode;
}
