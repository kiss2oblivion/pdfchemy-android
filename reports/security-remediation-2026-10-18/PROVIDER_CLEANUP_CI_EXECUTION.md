# Correct provider-failure cleanup ownership assertion

Release: HOLD. Base source: 8ec4cfe53b0bff7416de45c1f35e5a6fed4bb4bd.

CI 40022950917 completed all 275 methods on each API24/30/34/36. API24, API30 and API36 passed. API34 reported one failure in separateProvider_failingOrMissingProvider_failsGracefullyWithoutOrphans: its before-set contained an earlier snapshot while its after-set was empty. The dependent build job was skipped. The actual Reader lifecycle method passed on API34 as well as the other API jobs; the exact signed APK also passed the public-UI Reader/Editor/Reader/Home workflow.

The provider test required the entire shared staging-cache filename set to remain identical. That condition also fails when an older, unleased session is legitimately reclaimed. An empty after-set contains no new orphan. The failed assertion did not establish which asynchronous owner retired that particular old snapshot.

The revised fixture holds a separate snapshot lease and retires an unleased control after recording the before-set. With the original equality assertion, that deterministic native baseline fails with two expected filenames and one remaining filename. The corrected test requires no newly orphaned files, the separately leased snapshot to remain present with its lease, and its exact original bytes to survive. Both fixtures are released in finally.

## Verification snapshot

- Deterministic native baseline: one method, one expected assertion failure, retained raw evidence.
- Corrected complete SeparateSenderAdmissionSecurityTest class: 12 methods pass on the owned offline, settled API36 emulator; zero failures, skips or host crashes.
- Native test assembly passes. The application APK is identical before and after this test repair, SHA-256 0867a7362ec2d880769e360e2441184ba405666443ccbe28d10d8ebf5ae44a91. Production source is unchanged.
- The total native suite remains 275 methods per API. New resulting-source CI is required; the previous failed run is retained.

Raw evidence is outside the checkout under codex-final-8ec4cfe5-ci-failure.log, codex-final-8ec4cfe5-ci-api34-failure/, codex-final-8ec4cfe5-ci-completion-failed.json, provider-cleanup-baseline-build.log, provider-cleanup-api36-baseline/, provider-cleanup-fixed-build.log and provider-cleanup-api36-fixed/. Portable identities are in evidence/PROVIDER_CLEANUP_CI_RECEIPT.json.

Final signed-source binding, complete resulting-source CI and the external release gates remain separate. No retries, omitted tests or risk acceptance are counted as closure in this receipt.
