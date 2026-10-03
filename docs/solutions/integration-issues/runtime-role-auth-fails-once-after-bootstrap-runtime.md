---
title: "Runtime role authentication fails once after bootstrap-runtime, then passes on rerun"
date: 2026-10-02
category: integration-issues
module: supabase-migration
problem_type: integration_issue
component: database
severity: low
symptoms:
  - "First SupabaseLiveSmokeTests run after `bootstrap-runtime` fails with a database authentication error for the ai_interview_runtime role"
  - "An immediate rerun with no configuration change passes"
  - "Repeated after both the V17 and V18 rollouts (each followed by a runtime-role bootstrap)"
root_cause: async_timing
resolution_type: workflow_improvement
related_components:
  - infrastructure
  - testing_framework
  - documentation
tags:
  - supabase
  - supavisor
  - session-pooler
  - runtime-role
  - bootstrap-runtime
  - live-smoke
  - password-rotation
  - flaky-first-run
---

# Runtime role authentication fails once after bootstrap-runtime, then passes on rerun

## Problem

Right after `./gradlew supabaseMigrate --args=bootstrap-runtime --no-daemon` resets the runtime role's password, the first live smoke run fails database authentication for that role. An immediate rerun with no changes passes. It looks like a bad credential but is not one.

## Symptoms

- `SUPABASE_LIVE_SMOKE=true ./gradlew integrationTest --tests '*SupabaseLiveSmokeTests' --rerun-tasks --no-daemon` fails with a database authentication error for `ai_interview_runtime`, in the first run after a bootstrap.
- The same command, rerun immediately with no change to `.env.supabase`, the code, or the database, passes.
- Seen on more than one rollout in this session: after V17 and again after V18, each followed by `bootstrap-runtime`.
- The bootstrap itself exits cleanly ("Supabase database operation completed"), so nothing points at the bootstrap as the failing step.

## What Didn't Work

Every tempting reaction treats the failure as a real credential problem, and several of them restart the cycle:

- Choosing a new runtime password and re-running the bootstrap. Only the bootstrap sets that password (`SupabaseMigrationMain.kt:102-110`), so this is a fresh password change and the next first run can fail the same way.
- Editing `DATABASE_PASSWORD` in `.env.supabase`. The value was already correct; the file is read as Java Properties and is not the problem.
- Re-running `bootstrap-runtime`. It migrates, then issues another `ALTER ROLE ... WITH PASSWORD` (`SupabaseMigrationMain.kt:31-32`, `:110`). If the explanation below is right, that starts a new window in which the pooler may still serve the previous credential.

The only move that worked was doing nothing: run the smoke again.

Two failures that look similar are different problems; do not apply this workaround to them (session history):

- A pooler `:econnrefused` on live requests, plus a JDBC "authentication-query timeout", seen right after a runtime-role refresh during a Supabase latency incident. It did not clear on a rerun; treat it as connectivity, not credential propagation.
- SQLState `28P01` from the migration login (`SUPABASE_MIGRATION_PASSWORD`) before any bootstrap. That was a genuinely wrong project database password and persisted until the real one was supplied.

## Solution

Rerun the smoke once, unchanged, and wait a short while first if you want to be tidy. If the rerun passes, the credentials were fine. Treat an authentication failure as real only if it persists across a rerun (and a short wait) with no changes in between.

How the pieces fit, as read from the tree:

- `supabaseMigrate` is a `JavaExec` task that runs `SupabaseMigrationMain` (`build.gradle.kts:81-87`). Its default action is `migrate`; `bootstrap-runtime` is the only other accepted argument (`SupabaseMigrationMain.kt:26-34`).
- `bootstrap-runtime` reads `DATABASE_PASSWORD` (`:29`), runs `migrate()` (`:31`), then `bootstrapRuntime(password)` (`:32`). That function resets the role's password with `ALTER ROLE ... WITH PASSWORD` (`:102-110`) and re-applies the grants in the same transaction (`:111-129`).
- The migration connection and the smoke's runtime connection both go through the Supabase session pooler (`docs/supabase-migration.md:17` and `:20` show `<session-pooler-host>` for `SUPABASE_MIGRATION_URL` and `DATABASE_URL`; the runner enforces the pooler host for the migration URL at `SupabaseMigrationMain.kt:345`, and the app does the same for `DATABASE_URL` at `SupabaseDatabaseConfiguration.kt:44`).
- The live smoke is `src/integrationTest/kotlin/dev/jiaming/ai_interview/supabase/SupabaseLiveSmokeTests.kt`. It is gated by `@EnabledIfEnvironmentVariable(named = "SUPABASE_LIVE_SMOKE", matches = "true")` (`:19`), so ordinary `check` runs skip it. It reads `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD` from `.env.supabase` and opens a runtime-role connection with `DriverManager.getConnection` (`:35-45`). That is where the authentication error surfaces.
- The runbook documents the commands (`docs/supabase-migration.md:38` for bootstrap, `:108` for the smoke, `:135` for the post-V18 bootstrap). It does record the retry, but only as status history (`:99`, `:115`, `:138`: passed on retry or after the new runtime password propagated). Nothing next to the commands tells the reader to expect it.

## Why This Works

Per this session's observation, and the same conclusion in an earlier Codex session that hit it on an earlier rollout (session history; the runbook records a retry at the V9 bootstrap, `docs/supabase-migration.md:99`), the likely cause is that the Supabase session pooler (Supavisor) keeps authenticating with the previous credential for a short time after the role's password changes, so a connection made right after the bootstrap is checked against stale state. This is a working explanation, not a proven one; it has not been confirmed against pooler logs or Supabase documentation. What is verified is narrower: the first smoke after a bootstrap fails authentication, an immediate rerun with nothing changed passes, on more than one rollout.

Given that, a rerun works because time passes, not because anything is fixed. This also explains why re-copying or re-bootstrapping does not help: each one changes the password again and resets the clock.

## Prevention

- Before treating a post-bootstrap authentication failure as real, rerun the smoke once, or wait briefly and rerun. Only chase credentials, `.env.supabase`, or the role if the failure survives a rerun with no changes.
- Add a one-line note to `docs/supabase-migration.md` beside the bootstrap and smoke commands (`:38` and `:108`, and the `:135` block), for example: "If the first smoke after `bootstrap-runtime` fails authentication for the runtime role, rerun it once before investigating; the new password can take a moment to propagate through the pooler." The existing mentions at `:99`, `:115` and `:138` are status notes, not guidance.
- Suggestion only, not implemented: add a short bounded retry (a few attempts over several seconds) around the runtime connection in `SupabaseLiveSmokeTests` (`:45`), limited to authentication failures. That would hide the delay from whoever runs it; keep it bounded so a genuinely wrong password still fails.
- Do not respond to the failure by rotating or re-setting the password again.

## Related Issues

- `docs/supabase-migration.md`: the runbook for `bootstrap-runtime`, the live smoke and the V18 rollout; its dated status notes record the same retry.
- `docs/plans/2026-09-30-1657-feat-frontend-backend-supabase-continuation-plan.md` (KTD17): re-running the runtime-role bootstrap after every privileged migration release is what makes this recur on each rollout.
