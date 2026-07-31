---
name: commit
description: MANDATORY skill for ALL commits. Must be used EVERY TIME before creating any git commit. No exceptions.
---

# Generating Commit Messages

## Mandatory Process

**BEFORE ANY git commit COMMAND:**

1. **ALWAYS** run `git diff --staged` first to see changes
2. **ALWAYS** analyze the staged changes thoroughly
3. **ALWAYS** split the code changes into atomic commits, one per coherent / cohesive change. Tests for a change should be in the same commit as the change itself. A single feature spanning multiple files (module + view + test) is ONE cohesive change — do not split it by file. Only split when changes are truly unrelated (e.g. a bug fix + a new feature + a docs update)
4. **ALWAYS** run the checks relevant to the change before committing (all gradle; they need `git submodule update --init` and an SDK location — `ANDROID_HOME` or `sdk.dir` in `local.properties`):
    - Compile: `./gradlew assembleDebug` for any Java or resource change — the reliable gate, there is no other type-check
    - Lint: `./gradlew lint`, then **read `build/reports/lint-results-debug.txt`** — `abortOnError false` means a zero exit code doesn't mean clean
    - Tests: `./gradlew testDebugUnitTest --tests '<pattern>'` when a test under `src/test/java` covers the change (the aggregate `test` task rejects `--tests`). Note the suite is **already red on `master`** (`Slob$Strength` static-init NPE under `returnDefaultValues`) — compare against that baseline rather than expecting green
5. **ALWAYS** generate a commit message following the format below
6. **NEVER** commit automatically as a side effect of making code changes. Only commit when the user explicitly invokes the commit skill or says "commit".

## Confirmation Before Committing

**`--auto`** (caller runs autonomously): skip all approval/confirmation waits here (commit-plan gate below + fixup/rebase) — commit directly. Diff review, atomic splitting, checks, message format unchanged.

User trust requires seeing the plan before execution. Always present the full commit plan and wait for explicit approval before running any `git commit` command.

**For each commit (regular or fixup), present:**

- The commit message (header + body if applicable)
- The list of files included
- If splitting into multiple commits: the full split plan (which files go in which commit, in what order)
- If fixup: which commit SHA it targets and why

**Then ask the user to confirm.** Do not proceed until they approve. If they request changes to the message or grouping, adjust and re-present.

This applies equally to regular commits, fixups, and any commits triggered during the open-pr workflow.

## Auto-Fixup Detection

Before creating a new commit, check whether the staged changes should be fixup'd into a recent commit on the current branch.

**Process:**

1. Run `git log master..HEAD --oneline` to list all commits on the branch since diverging from `master`
2. For each staged file, check `git log master..HEAD -- <file>` to see if it was modified in a recent branch commit
3. If a staged change clearly amends or extends code from a previous commit (same file, nearby lines, related logic — e.g. fixing a typo introduced in a prior commit, adding a missing import for a recently added module), suggest fixup'ing into that commit
4. Present the suggestion: "This change to `<file>` looks like it should be fixup'd into `<sha> <message>`. Want me to fixup instead of creating a new commit?"

**When fixup is confirmed:**

1. Run `git commit --fixup=<sha>` (with user confirmation)
2. Then run `GIT_SEQUENCE_EDITOR=true git rebase --interactive --autosquash master` to squash immediately (with user confirmation before the rebase)

If the change doesn't clearly relate to a previous commit, proceed with a normal new commit.

## Required Commit Message Format

This repo uses Conventional Commits **by convention only** — nothing enforces it (no commitlint, no git hooks, and `.github/workflows/release.yml` is `workflow_dispatch`, so no CI runs on pull requests). A malformed message is caught by nobody, so get it right by hand. The format is fixed:

```
<type>(<scope>): <subject>
<BLANK LINE>
<body>
<BLANK LINE>
<footer>
```

The **header** is mandatory; **scope**, **body**, and **footer** are optional.

### Header

**Shape:** `<type>(<scope>): <subject>`

- `<type>` is one of: `build` `ci` `docs` `feat` `fix` `fix-next` `perf` `refactor` `release` `style` `test`
- `<scope>` (optional) is the affected area — usually the Java package under `src/itkach/aard2/` or the resource area touched: `lookup`, `article`, `dictionaries`, `dictionary`, `mdict`, `stardict`, `slob`, `descriptor`, `prefs`, `settings`, `widget`, `audio`, `utils`, `ui`, `manifest`, `proguard`, `fastlane`, `ci`
- `<subject>`: imperative present tense ("change" not "changed"), lowercase first letter, no trailing period
- **No line may exceed 100 characters**

**Examples:**

```
docs(README): add submodule init to build setup steps
fix(mdict): resolve @@@LINK= redirects to the target entry
```

### Body

ONLY add a body when the header alone isn't enough for a reviewer:

1. Use the imperative present tense, same as the subject
2. Explain WHAT changed only if the commit touches more than 3 files
3. Explain WHY — the motivation, contrasted with previous behavior
4. Keep every line under 100 characters

### Footer

- Reference the issue this commit closes: `Fixes #<issue>` / `Closes #<issue>`
- Breaking changes start with `BREAKING CHANGE:` followed by a description and migration path

### Revert

A commit that reverts another begins with `revert: ` followed by the reverted header. The body states `This reverts commit <hash>.`

## Co-Authored-By

Only add a `Co-Authored-By` trailer when Claude actually wrote the code being committed. If the user wrote the changes themselves (and Claude is just committing), do not add it.
