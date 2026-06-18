# Job: Phase 0 repo scaffold and docs

Branch: agent/2026-06-18_phase0-scaffold

## Objective

Create the full ZeroVPN repo structure: directories, docs, job packs,
README, LICENSE, .gitignore. No implementation code.

## Context

This job is already mostly done by Jimothy directly. This job pack
exists as a record of what Phase 0 delivers. See `PROJECT_BRIEF.md`
section 11 (Phase 0) for the deliverable list.

## Scope

- `README.md`
- `LICENSE` (MIT)
- `.gitignore`
- `docs/ARCHITECTURE.md`
- `docs/THREAT_MODEL.md`
- `docs/MODES.md`
- `docs/PROVIDER_LANES.md`
- `docs/CODEX_WORKFLOW.md`
- `docs/VOLUNTEER_NETWORK_MODE.md`
- `docs/TUBEPULSE_UI_REPORT.md`
- `jobs/000_phase0_repo_scaffold.md` (this file)
- `jobs/001_android_shell.md`
- `jobs/002_wireguard_research.md`
- `jobs/003_node_installer.md`
- `jobs/004_tubepulse_ui_mining.md`
- `jobs/005_volunteer_network_research.md`
- Directory structure: `android/`, `node/install/`, `node/scripts/`,
  `node/templates/`, `infra/oracle/`, `infra/azure-student/`, `docs/`,
  `jobs/`

## Non-goals

- No Android code
- No node installer scripts
- No Terraform or infrastructure code
- No dependencies chosen yet

## Constraints

- Windows-native PowerShell
- No WSL
- MIT license
- No secrets in git

## Expected files changed

All files listed in Scope above.

## Tests / validation

- `git status` shows all files
- Directory structure matches brief section 8
- README contains product framing from brief section 2
- THREAT_MODEL covers all items from brief section 10
- No code files exist yet

## Done when

- [x] All docs created
- [x] All job packs created
- [x] Directory structure matches brief
- [x] Initial commit pushed to GitHub

## Report back with

- summary
- files changed
- commands run
- tests passed/failed
- manual checks still needed