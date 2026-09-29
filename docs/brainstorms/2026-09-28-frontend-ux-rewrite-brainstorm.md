# Frontend and UX Rewrite - Brainstorm

- **Date:** 2026-09-28
- **Status:** Decisions captured; ready for planning
- **Platform context:** AWS hosting with Supabase Postgres (pgvector) as the database. GCP is dropped; the GCP plan in
  `docs/plans/2026-09-28-1159-feat-target-stack-architecture-plan.md` is superseded.
- **Phase scope:** No sign-in in this phase. The rewritten app runs privately (developer and testers)
  with the existing single local user. Accounts, the visitor trial, and public launch come in a later
  auth phase.

## Goal

Rewrite the Next.js frontend and UX around a clearer journey: score the resume first, use the
candidate's saved history to tailor it for a target job, then practice by chat or voice.

## Decisions

| Area | Decision |
|---|---|
| Resume score | General quality score always; job-fit score and job-specific feedback when a target job is added |
| Score context | Optional job title; no seniority. AI infers level from the resume and target job |
| Resume feedback | Ranked fixes plus suggested rewrites of weak bullets (copyable). No in-app editor |
| Resumes | Flat list of named resumes; user names each upload and chooses which one to use |
| Target jobs | Pasted job description saved as a named, reusable target job. No URL import |
| Past-experience suggestions | Used to tailor the resume only. Show the match, why it fits, and guidance on presenting it. No generated bullets |
| Experience sources | Other saved resumes, plus optional "Add a project" form and optional pasted LinkedIn Experience text |
| Modes | Chosen before questions are generated: **Practice (chat)** or **Mock interview (voice)** |
| Practice (chat) | AI decides the question mix from the job description; each question has a "why this question" tag; user can add their own question; instant feedback per answer; answer versioning |
| Mock interview (voice) | Ships after the text flow. Report links the weakest answers into Practice |
| Main screen | Guided flow plus a library side menu (resumes, target jobs, experiences, history) |
| History | Lists plus small trends: resume scores, per-question score change across attempts, voice reports |
| Deletion | Users can delete resumes, target jobs, and added experiences (and their derived data) |
| Language | English only in v1 (UI, feedback, interviews) |
| Devices | Desktop first; pages still fit a phone; voice recommended on desktop |
| Look and feel | Calm and professional: clean, lots of white space, neutral colors with one accent |
| Sign-in | None in this phase. Private/dev use only, single local user |
| Frontend foundation | Newest Next.js (16) and React 19, TanStack Query for server data, a new visual design built on Tailwind CSS and shadcn/ui |

## Journey (this phase)

1. **Resume** - pick a named resume or upload a new one; optional job title.
2. **Score** - general score, ranked fixes, suggested rewrites.
3. **Target job** - paste a job description; saved as a named target job.
4. **Job fit** - fit score, job-specific feedback, past-experience suggestions with guidance.
5. **Mode** - Practice (chat) or Mock interview (voice).
6. **Practice** - answer, get feedback, answer again; attempts are versioned.
   **Mock interview** - microphone check, live interview, report; weakest answers link to Practice.
7. **History** - lists with small trends.

## Assumptions to confirm

- Every answer attempt is kept, with the score change from the previous attempt shown.
- Past-experience suggestions appear only after a target job is added.
- The AI decides the number of practice questions within a cost cap (for example, at most 8).
- Voice keeps the earlier AWS plan's shape: 15 minutes, five main questions, at most one follow-up each, no stored audio.

## Risks and cheapest tests

1. **Not enough history for suggestions.** Most users have one or two resumes. Mitigated by the
   optional project form and LinkedIn paste. Test: share of users who add a second resume or experience.
2. **Unstable scores.** If rescoring the same resume moves the number noticeably, users stop trusting it.
   Test: rescore a fixed set of resumes several times before launch.
3. **AI-chosen question quality.** Test: review question sets for a small set of real job descriptions.
4. **Shared identity.** Until the auth phase, anyone with access to a deployment sees every saved resume
   and all history. Keep deployments private.

## Backend implications

- Keep every query scoped by user ID through the existing local-user seam, so the auth phase can swap in
  real users without reworking ownership.
- Remove seniority from the flow and prompts.
- Split the analysis job: general score and job fit become separate steps; questions are generated
  only after the mode is chosen.
- New data: resume names, target jobs, experience items, answer attempts, user-added questions,
  question rationale.
- Retrieval across all of the user's resumes and experience items for a target job.
- Voice report to Practice link.

## Not in this phase

Sign-in, the visitor trial, and public launch (auth phase); in-app resume editor and export; job URL
import; generated bullets for past items; "generate more questions"; side-by-side attempt comparison;
deleting practice history; whole-account deletion; Chinese UI; mobile-first design; full progress
dashboard.

## Deferred to the auth phase

These were decided in the brainstorm and apply when sign-in is added:

- **Entry:** try first, then sign up. The trial takes one resume plus a job title and seniority, shows
  the general score and top fixes in full, then asks the visitor to sign up; trial data carries over.
- **Sign-in methods:** Google, GitHub, email + password.
- **Trial limits:** a per-IP limit, a global daily cap, and cleanup of unclaimed trial data after about 7 days.

## Open items

- Choose the auth provider for the auth phase (Supabase Auth or Cognito). Supabase Auth supports anonymous users
  that upgrade on sign-up; Cognito needs a guest session claimed at sign-up and an OIDC bridge for GitHub.
- Update the AWS deployment plan's auth and database sections to match.

## Next steps

1. Plan the frontend rewrite and the backend changes above.
2. Wireframe the key screens: score, job fit, mode choice, practice, history.
