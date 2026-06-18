# Codex / ChatGPT Coding-Engine Workflow

Jimothy is the orchestrator. Codex/ChatGPT is the coding engine. The
project owner is Aaron.

## Hard constraints

- **Windows-native PowerShell only.** No WSL unless Aaron explicitly
  approves.
- **No loose chat prompts.** Every coding task must be written as a job
  pack first.
- **Disposable clones.** Codex works in a clone or worktree, not the live
  repo. Use branches named `agent/YYYY-MM-DD_short-task-name`.
- **Report, don't claim.** Codex returns a diff/report, not just "done".
- **Review before apply.** Jimothy reviews and applies changes
  deliberately.
- **Docs first.** Documentation is written before or alongside
  implementation. No code without a documented decision.

## Job-pack template

Every coding task starts as a job pack in `jobs/`:

```markdown
# Job: <short title>

Branch: agent/YYYY-MM-DD_<short-name>

## Objective

What this job accomplishes.

## Context

Background the coding agent needs. Link to docs, not inline them.

## Scope

What files and components this job touches.

## Non-goals

What this job explicitly does NOT do.

## Constraints

Technical and policy constraints. Windows-native, no WSL, etc.

## Expected files changed

List of files that should be created or modified.

## Implementation notes

Guidance for the coding agent. Not a full spec — the agent has
discretion within the scope.

## Tests / validation

How to verify the work. Build commands, manual checks, etc.

## Done when

Acceptance criteria. All must be met.

## Report back with

- summary
- files changed
- commands run
- tests passed/failed
- manual checks still needed
```

## Workflow

1. Jimothy creates a job pack in `jobs/`.
2. Jimothy spawns Codex with the job pack as the prompt.
3. Codex works in a disposable clone on a branch.
4. Codex returns a diff/report.
5. Jimothy reviews the diff against the job pack acceptance criteria.
6. If accepted, Jimothy applies the changes to the live repo.
7. If rejected, Jimothy revises the job pack and re-spawns.

## Rules for Codex

- Do not push to `main` or `master`.
- Do not force-push.
- Do not modify files outside the job's scope.
- Do not add dependencies without documenting them.
- Do not add secrets to git.
- Do not add telemetry, analytics, or third-party SDKs.
- Return a diff and a report. "Done" is not a report.