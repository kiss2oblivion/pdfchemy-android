# Release-message localization

Release: HOLD. Base: `3be961ca9da9f74ca8eac7a2f64210d91f95925b`.

The 38 historical MissingTranslation baseline entries were represented by English placeholders in localized resources. This batch replaces 725 of those placeholders across 20 language/region resource sets, covering 760 key/locale pairs. Existing translated values are preserved, including the Portuguese/Brazilian Portuguese and Simplified/Traditional Chinese variants. No unrelated string value or resource key changes.

The covered UI includes the default PDF viewer instructions, file-access recovery, memory-capacity guard and recovery actions, Reader/foldable modes, scanned-document detection and on-device OCR. The English default-viewer instructions were checked through Android's resource compiler and already render line breaks correctly; their source is unchanged.

## Verification

`ReleaseLocalizationTest` uses compiled Android resources through Robolectric API33. Its baseline run has two methods: the English line-break control passes; the locale-specific case fails because the German viewer title is still English. Raw baseline XML and the former 38-entry baseline are preserved outside the checkout in `release-localization-baseline-results/`, with the original build log `release-localization-baseline.log`.

After correction, both methods pass. They check all 20 supported language/region selections, line-break rendering, quoted instruction text, capacity-message formatting with all arguments, and page-number formatting.

An independent source comparison against the base commit checks every covered key, duplicate names, exact format-argument types/indices, unchanged unrelated string values and an empty suppression baseline. It passes with 38 keys, 760 coverage pairs and 725 replacements. Portable source identities are in `evidence/LOCALIZATION_RECEIPT.json`.

Release lint passes with zero errors and 640 warnings. The complete host unit suite then passes: 264 tests, zero failures/errors/skips. The source release gate also passes with 14 hashed fixtures and 62 smoke routes, and no remaining lint-baseline entries. Raw post-fix resource/lint output is in `release-localization-fixed.log`; complete host results are in `release-localization-full-host-results/` and `release-localization-full-host.log` outside the checkout.

Commit, push, resulting-source CI and signed final artifacts are pending at this source snapshot. Broader translation review and physical layouts are separate from these checks; no physical-device or Play acceptance is claimed.
