# PDFchemy Android — UI/UX Audit Directive for Codex

This file retains the user's audit scope and requirements. It is the governing brief, not an implementation authorization.

## Mission

Perform a full UI/UX audit of PDFchemy Android. This is an audit-only phase.

Do not modify production code, redesign screens, create replacement components, or change layouts, navigation, copy, icons, ads, onboarding, settings, Reader Mode, or interaction behavior.

Inspect the app as it exists, understand its intended product philosophy, identify actual UI/UX problems, and produce a detailed report. The current visual design is the baseline, not something to replace for novelty. Any proposed change must be justified by evidence.

## 1. Product Intent

PDFchemy is a local-first document application intended as a Swiss-army knife for PDFs and document workflows. The user should feel: “This app can do almost anything I need without making me work for it.”

Target experience: premium, slick, polished, fast, direct, powerful, predictable, unobtrusive, and user-controlled. Design around the person rather than internal feature architecture. Do not transfer software complexity onto the user.

## 2. Core UX Principle

The interface must make its large feature surface feel simpler than it actually is. Power belongs underneath the interface. Users should not need to understand internals.

Expected flow: choose what you want → choose the document → sensible defaults → optional advanced controls → run → clear result. Common workflows should generally be within one or two meaningful taps from the relevant surface.

## 3. Current Visual Design Is the Baseline

The current visual direction is good. Do not assume a visual redesign is needed. Do not recommend “modernize everything”, a replacement design system, imitation of another app, or a different navigation model without strong evidence of genuine usability problems.

Evaluate consistency, spacing, hierarchy, alignment, discoverability, clarity, responsiveness, animation quality, interaction cost, and visual balance without redesigning for its own sake.

## 4. Product Personality

Professional, concise, confident, calm, clear. No playful, cute, gimmicky, overly conversational, or childish language. No jokes, mascots, quirky errors, marketing slogans, or unnecessary microcopy. Personality may come through quality and attention to detail.

## 5. Dashboard Philosophy

Keep the existing dashboard concept: slick, polished, premium, immediately useful, and centered on tool access. It is not a telemetry dashboard.

Do not add usage statistics, storage-saved counters, productivity scores, gamification, recent-activity analytics, personalized behavioral suggestions, or usage-based tool promotion. PDFchemy does not learn preferred tools. No behavioral adaptation through usage analytics.

## 6. Tool Organization

Keep visual categories/cards. Potential concepts include Organize, Optimize, Convert, Secure, Inspect, Extract, and Edit. Evaluate logical grouping, naming, consistency, discoverability, overload, and duplicate conceptual placement. Do not flatten into an unstructured tool grid unless a serious problem is demonstrated.

## 7. Tool Search Is Mandatory

Audit discoverability, speed, reliable tool-name matching, reasonable conceptual queries, correct destination, and unnecessary taps. Users who know their intention should not need to browse categories.

## 8. No Behavioral Personalization

Do not recommend recently used/frequently used tools, recommendations, smart ordering, AI suggestions, usage-based ranking, or behavioral personalization unless history-free behavior has been explicitly requested. Deterministic UI behavior is intended.

## 9. File Selection Philosophy

Compress, Merge, Split, OCR, Protect, Sign, Rotate, Delete Pages, and similar tools should generally reach file selection first. Selected input must be obvious. Audit intermediate screens, redundant selection, selected-state loss, and source/output confusion.

## 10. File Selection Should Feel Like PDFchemy

Audit continuity among PDFchemy, Android document picker, selected-document state, and configuration: context loss, visual discontinuity, return state, redundant confirmation, and unnecessary picker invocation. Do not bypass Android security/storage for visual consistency.

## 11. Multi-File Operations

Merge should have visible selected files, clear filenames/thumbnails where useful, drag/reorder, removal, obvious ordering, and easy addition. Users should never wonder which file comes first.

## 12. Page-Level Operations

Split, Delete, Rotate, Reorder, and Extract Pages should use a visual page workspace where practical: thumbnails, direct manipulation, selection, page numbers, ordering, undo/redo. Audit mental mapping between numerical pages and content.

## 13. Undo / Redo

Editing-session modifications should support undo/redo where technically practical. History remains until commit/save. Audit irreversible actions, missing undo, ambiguous commit boundaries, state loss, and navigation destroying progress.

## 14. Sensible Defaults

Avoid technical decisions before basic completion. Simple defaults plus optional Advanced. Basic flow: select file → review defaults → process. Audit excessive default-screen settings.

## 15. Advanced Controls

Prefer a dedicated advanced screen sliding from either side. Audit discoverability, preserved changes on Back, smooth transitions, grouping, and insulation from complexity. Do not crowd large configuration sets onto one page.

## 16. User-Configurable Defaults

Where sensible, support recurring compression profiles, Reader behavior, output behavior, and possibly tool options. Audit discoverability, predictability, reset, and consistency with actual tool settings.

## 17. Reader Mode — Highest UX Priority

The user opened a PDF to read the PDF. The document owns the screen. Reader should be distraction-free, fast, fluid, minimal, and stable.

## 18. Reader Chrome

Controls should not permanently occupy unnecessary space. Clean PDF → tap → command centre → tap/scroll as appropriate → controls disappear. Minimal polished command surface; no continuous competition with content.

## 19. Reader Command Centre

Expose relevant capabilities without clutter. For ambiguous icons, evaluate long-press tooltip, accessible label, context description, or brief tooltip. Mobile has no true hover. Do not overload the main Reader with labels.

## 20. Reader Capabilities Benchmark

Use mature readers, especially Adobe Acrobat, as a feature baseline, not visual template. Evaluate continuous scrolling, single-page, appropriate two-page, pinch/double-tap zoom, navigation/jump/thumbnails/search/bookmarks/share/print/rotation, annotations/highlight/underline/draw/notes/shapes/signatures. Do not automatically copy Adobe's architecture.

## 21. Reader Interaction Quality

Reader should feel smooth as butter. Evaluate scrolling, page transitions, pinch/double-tap zoom, gesture latency, thumbnails, rendering, chrome reveal/hide, annotation-toolbar opening, rotation, search-result navigation, and memory pressure. Flag visible jank.

## 22. Reader State

Last page/reading position and possibly reading-mode preferences may be remembered, but users can enable/disable. Audit clear local predictable easy-to-change preference.

## 23. Reader → Tools Transition

An already-open PDF must remain the input when technically valid. Do not force selecting the same PDF again. Audit all transitions for reselection/state loss.

## 24. Reader Annotations

Support annotation without permanent clutter. Preferred concept: Reader → Annotate → secondary annotation toolbar. Audit discoverability and friction.

## 25. Advertising Philosophy

Free use must not feel punished. An ad must never distract from the actual task. Audit every placement.

## 26. Reader Advertising

Reader is essentially ad-free. Ads must not overlay/cover PDF, shift geometry, steal gestures, cause layout jumps, interrupt reading/navigation, or appear between pages. An outside banner is only a hypothetical experiment, not a requirement, judged harshly for reading impact. Default: keep Reader clean.

## 27. Acceptable Ad Contexts

Dashboard/menu, tool configuration, editing/organization, and post-operation natural breaks may be acceptable. Examples: Split, Merge, Delete, Organize, compression/modification. Evaluate actual natural placement; an empty rectangle does not justify an ad.

## 28. Interstitial Ads

Only natural breaks. Never interrupt reading, selection, active editing, critical confirmation, progress, annotations, or navigation. Audit timing and placement.

## 29. Premium Placement

Keep the existing Premium button unless a demonstrable UX defect exists. Discoverable, not repeatedly pushed. No aggressive upgrade prompts.

## 30. Settings

Existing structure is broadly correct; audit, do not redesign without issues. Relevant settings: theme, preferred save destination, reading-position memory, haptics, Reader/tool defaults, privacy/about, Premium. Avoid a settings jungle.

## 31. Existing Onboarding

Audit the existing onboarding: length, clarity, need for each screen, Skip, delay to first useful action, meaningful concepts. Do not design a replacement or add onboarding because a feature exists.

## 32. Existing Haptics

Audit consistency, appropriateness, excessive vibration, and missing meaningful feedback. No system redesign without evidence.

## 33. Empty States

Simple message and one relevant action, for example “No recent files yet.” / “Open a PDF”. Avoid decorative marketing or unnecessary illustration unless it genuinely helps comprehension.

## 34. History / Recent Files

Dedicated entry point; do not dominate dashboard. Audit discoverability, clarity, privacy, removal, empty state, and availability when source disappears.

## 35. Output Naming

Propose sensible editable names: `document_compressed.pdf`, `document_ocr.pdf`, `document_split.pdf`, `document_merged.pdf`. Audit clarity, extensions, collisions, and user control.

## 36. Preferred Output Location

User chooses preferred location, locally remembered if desired, always changeable. Audit obvious predictable destination behavior.

## 37. Filename Collisions

Offer overwrite OR automatically renamed copy, such as `document.pdf` / `document (1).pdf`. Audit understandable safe choice.

## 38. Completion Screen

Successful result provides Open, Share, Show in Files, Another Operation, Home. Never auto-open; user decides next.

## 39. Progress Experience

Human progress: page18 of62, compressing images, applying OCR, saving output; not just Please wait. Cancel when technically safe. Audit predictable cancellation.

## 40. Error Messaging

Explain what is wrong, what could not complete, and next action. Examples: damaged PDF, encrypted/password needed, selected file could not open, page could not render. No stack traces, Java exception/class names, internal services, Binder terminology, implementation details, or raw library errors. Details still must mean something to the user.

## 41. Original-Safe Messaging

Explain protection when relevant without nag screens. Where appropriate, Don't show this again. Audit repeated confirmations and recommend removing unnecessary modal friction.

## 42. Accessibility Scope

Not a compliance-driven redesign. Find genuine defects: large font broken layouts, truncated labels, contrast, tiny targets, missing TalkBack labels/inaccessible controls, landscape/tablet/foldable failure, one-handed usability, ignored reduced motion. Preserve visual identity.

## 43. Device and Layout Coverage

Small/large phones, landscape, tablets, supported foldables, light/dark, large fonts. Inspect stretching, empty spaces, crowding, broken grids, dialogs, and navigation distance.

## 44. Performance Is UX

Inspect main-thread work, recomposition, janky animation, transitions, blocking files, thumbnails, rendering, search, delayed feedback, layout jumps, frozen progress. Separate backend time from UI responsiveness: a long operation is acceptable; a frozen interface is not.

## 45. Animation Standard

Subtle, fast, purposeful; communicate navigation, state, hierarchy, continuity. No performance for the user. Flag long/bouncy/flourished/blocking/inconsistent motion.

## 46. Zero-Tolerance UX Problems

Flag unnecessary taps, clutter, modal spam, distracting advertising, technical errors, hidden functions, inconsistent controls, sluggish Reader, reselection, state/undo loss, unpredictable controls, ad/content shifts, unclear save and selected-document state.

## 47. Audit Every Major Surface

At minimum: Dashboard; categories/search; file/multi-file selection; Merge/Split/Organizer/Delete/Rotate/Reorder/Compression/OCR/Protect/Signing/Redaction/Repair/conversion; advanced settings; progress/results/errors/dialogs; Reader/command centre/PDF search/thumbnails/bookmarks/annotations/Reader-editing transitions; History/Settings/onboarding/Premium/ads; light/dark; phone/landscape/tablet; empty states. Include other user-facing surfaces.

## 48. Workflow Audit

Trace complete journeys, not isolated screenshots:

- A: launch → Compress → PDF → defaults → process → save → open result.
- B: launch → search merge → Merge → multiple files → reorder → merge → save → share.
- C: external PDF → Reader → read → search → annotate → tool → same PDF input → return Reader.
- D: Organizer → select → reorder → delete → undo → redo → save.
- E: operation fails → understandable explanation → recovery.
- F: long operation → useful progress → cancel → safe return.

## 49. Benchmarking

Compare Adobe Acrobat mobile, useful mature PDF software, Android conventions, and PDFchemy philosophy. Do not imitate blindly. Distinguish capability gap, interaction-quality gap, visual preference, and different equally valid design.

## 50. Evidence Standard

No vague “could improve”. Measure concrete friction. Example: four post-selection transitions versus two while retaining control. Every finding contains location, observed behavior, importance, severity, recommended direction. Use screenshots or exact code/component references where available.

## 51. Severity Classification

- P0 — Blocking: broken, destructive, inaccessible, effectively unusable workflow.
- P1 — Major: significant friction, confusion, lost state, poor Reader, serious inconsistency.
- P2 — Moderate: noticeable usability/polish worth fixing.
- P3 — Minor polish: spacing, animation, copy, icons, minor hierarchy.
- Observation: discussion item, not necessarily wrong.

Do not inflate severity.

## 52. Distinguish Facts from Preferences

Types: BUG, USABILITY DEFECT, PERFORMANCE DEFECT, ACCESSIBILITY DEFECT, CONSISTENCY DEFECT, POLISH ISSUE, SUBJECTIVE ALTERNATIVE. Subjective alternatives are not defects.

## 53. Preserve Working Design

Explicitly identify good screens, unchanged flows, reusable patterns, strong visual and interaction decisions. Not an arbitrary change hunt.

## 54. No Implementation in This Phase

Allowed: code/resources inspection, running app/UI tests, profiling, comparing flows, Compose hierarchy/navigation inspection. Prohibited: production editing, fixes/commits, refactor, design alteration. End with report.

## 55. Required Audit Report Structure

Title: PDFchemy Android — Full UI/UX Audit.

Required sections:

1. Executive Summary: quality, strengths, largest problems, redesign judgment, highest-value fixes.
2. Product Philosophy Assessment: premium, slick, direct, user control, power without effort, distraction-free reading.
3. What Is Already Good.
4. Navigation & Information Architecture.
5. File Selection & Document State.
6. Tool Workflows.
7. Advanced Controls.
8. Page Organization UX.
9. Reader Mode: experience, controls, gestures, zoom, navigation, search, annotations, performance, state, transitions.
10. Saving & Output.
11. Progress & Cancellation.
12. Errors & Recovery.
13. History.
14. Settings.
15. Onboarding.
16. Ads & Premium: each placement individually.
17. Accessibility & Device Layout.
18. Performance & Perceived Responsiveness.
19. Visual Consistency & Polish.
20. Competitor Benchmark.
21. Full Findings Table: ID, Severity, Surface, Type, Finding, User Impact, Evidence, Recommendation.
22. Zero-Tolerance Violations, or explicitly none.
23. Top 10 Highest-Value Improvements, ranked by impact/cost/risk, not cosmetic bias.
24. Changes NOT Recommended, mandatory.
25. Suggested Implementation Phases, proposed only.

Suggested future sequence: critical flow problems → Reader polish → tool consistency → performance/jank → minor polish. Do not implement.

## 56. Final Standard

Not “Can the user technically accomplish the task?” Instead: “Does PDFchemy make the task feel easier than the problem the user came to solve?”

For Reader: “Does the interface disappear enough that the user forgets they are using a PDF application and simply reads the document?”

## Final Directive

Audit first. Change nothing. Assume the visual language is intentional. Trace real workflows, not just screens. Benchmark mature software without copying. Measure friction and responsiveness. Identify real defects. Separate defects from preference. Preserve what works. Produce the report. Stop for review.
