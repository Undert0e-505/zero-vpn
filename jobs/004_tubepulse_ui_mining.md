# Job: TubePulse UI mining report

Branch: agent/2026-06-18_tubepulse-ui-mining

## Objective

Inspect the TubePulse repo and extract visual conventions, design
tokens, typography, button treatment, and build patterns for reuse in
ZeroVPN's Compose UI.

## Context

- ZeroVPN should "feel as simple as TubePulse" (brief section 5).
- TubePulse is React Native + Expo. ZeroVPN is Kotlin + Compose.
- Reuse visual/structural conventions only — do not copy product logic.

## Scope

- Read TubePulse source files
- Extract colour palette, typography, spacing, button styles
- Document Compose translation
- Write `docs/TUBEPULSE_UI_REPORT.md`

## Non-goals

- No code copying
- No TubePulse product logic
- No React Native patterns in ZeroVPN code

## Constraints

- Mine directly from source — do not guess
- Document the source file and line for each extracted token

## Expected files changed

- `docs/TUBEPULSE_UI_REPORT.md`

## Tests / validation

- Every colour token has a hex value and usage description
- Every typography token has a size, weight, and usage
- Compose translation code is provided for each token
- Build conventions are documented (JDK, SDK, signing, script pattern)

## Done when

- [x] Report written
- [x] All tokens extracted with source references
- [x] Compose translations provided
- [x] Build conventions documented

## Report back with

- summary
- file path
- token count extracted
- any patterns that don't translate cleanly to Compose