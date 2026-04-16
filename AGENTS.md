# AGENTS.md

## Backend Fix & Optimization Agent

This repository is a Spring Boot backend (`Java 17`, `Maven`, `Spring Web/JPA/Security/JWT`).
Use this agent when the task is to fix bugs, improve reliability, and optimize backend performance without breaking API contracts.

## Scope

- Primary code: `src/main/java/com/project/habit_tracker/**`
- Config: `src/main/resources/application.properties`
- Tests: `src/test/java/com/project/habit_tracker/**`
- Do not modify frontend/mobile repos from this agent.

## Working Rules

- Keep changes limited to the requested task.
- Prefer small, reversible patches.
- Preserve existing endpoint routes and DTO shapes unless explicitly asked to change them.
- Never commit secrets, tokens, or hardcoded credentials.
- Avoid broad refactors while fixing a single bug.

## Standard Workflow

1. Reproduce
- Run tests and/or start app to reproduce the issue.
- Capture exact failing endpoint, payload, stack trace, and expected behavior.

2. Diagnose
- Trace from `api` -> `service` -> `repository` -> `entity`.
- Check validation annotations, transaction boundaries, and auth rules.
- For data issues, inspect query usage and entity relationships.

3. Patch
- Apply the smallest safe fix first.
- Add/adjust validation, null handling, authorization checks, and transaction semantics as needed.
- For optimization, prefer query-level fixes and indexing guidance over premature caching.

4. Verify
- Run targeted tests, then full test suite.
- Confirm no regression in auth, habit CRUD, checks, mentorship flows, and error handling.

5. Report
- Summarize root cause, fix, tests run, and any follow-up risks.

## Commands

- Run tests:
```bash
./mvnw test
```

- Run app:
```bash
./mvnw spring-boot:run
```

- Package:
```bash
./mvnw clean package
```

- Run single test class:
```bash
./mvnw -Dtest=HabitTrackerApplicationTests test
```

## Optimization Checklist

- Eliminate N+1 query patterns in service/repository logic.
- Use repository methods that filter at DB level instead of in-memory filtering.
- Ensure pagination for list endpoints likely to grow.
- Keep entity fetch strategy explicit; avoid accidental eager graph loads.
- Ensure DB indexes for frequent lookups (e.g., user, habit, date keys, match IDs).
- Keep JWT parsing/validation centralized and lightweight.
- Reduce duplicate remote/database calls in a single request path.

## Reliability & Security Checklist

- Validate request DTOs (`@Valid`, constraints).
- Return consistent API errors via `GlobalExceptionHandler`.
- Verify users can only mutate/read their own resources unless role permits otherwise.
- Wrap multi-step writes in transactions where required.
- Handle race conditions around check toggles and mentorship messaging.

## File-Level Ownership Hints

- Controllers: `api/*Controller.java`
- Business logic: `service/*.java`
- Persistence access: `repository/*.java`
- Domain model: `entity/*.java`
- Auth/JWT/security chain: `security/*.java`
- API contracts: `api/dto/*.java`

## Definition of Done

- Build passes.
- Tests pass (or failures explicitly documented as pre-existing with evidence).
- Behavior matches requested outcome.
- No unrelated file churn.
- Clear handoff notes for any remaining migration/index/config work.
