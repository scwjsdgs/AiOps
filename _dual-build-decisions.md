# Dual-build cross-cutting decisions — alert refactor

Parent HEAD: 91430b0aaf5ae18917ca85344a4020c8a6ccbf4c

- Logic unchanged, boundaries only: no rule engine, no config widening, no behavior change. Denominators: base behavior at HEAD is the oracle; any logic change is a regression by definition.
- checkSuppress stays in AlertLifecycleService (with its cooldown-expiry side effect inline). SuppressionPolicy wraps it with per-entry post-actions; never copy the check, never move the expiry-clear into hit branches.
- SuppressionPolicy double entry: byFingerprint (by fingerprint latest; +1 occurrence, timestamp refresh, save) and byAnchor (check saved first, then findCoolingSibling by serviceName in PENDING/FAILED, +1 sibling, deleteById(saved)). Side effects must stay per-entry; never unify.
- shouldBreachDedup splits: Redis actions in FingerprintService (tryDedup, releaseAndReoccupy), the breach decision stays in the Webhook orchestration layer using CorrelationService.taskCanMerge. Fingerprint layer never depends on task state.
- Redis aggregation index: no in-memory pendingCorrKeys. Reverse index via Redis key (write PENDING with alertId payload, or webhook:corr:pending:{alertId}). fillCorrKeyWithTaskId keeps repeatWhenEmpty 500ms poll with 10s cap; do not make it one-shot.
- AlertCorrelationTest reflective target moves to CorrelationService.buildCorrelationKey; AlertLifecycleServiceTest constructor arity stays in sync with refactored components.
- Each step compiles + mvn test full-green before moving on; steps commit independently so any step can be reverted. ApprovalService line 319 stays untouched this round — do not touch it.
- Codex runs after each step (read-only sandbox): verify test green, gate review; stop on any red.
