# DL-039B immutable accepted-plan persistence checkpoint

## Android

Room schema version 8 adds one nullable `strategy_plan_snapshot` text column.
Migration 7 to 8 preserves legacy decision identity and leaves the plan null.
No current profile or runtime evidence is evaluated during migration. The
generated schema, migration, restart, retry, deferral, expired-lease recovery,
and corruption tests form the Android evidence.

## Apple

Queue format version 4 appends one nullable encoded complete-plan field.
Versions 1, 2, and 3 remain readable. Version-3 identity-only work remains null
rather than receiving a current plan. Successful writes upgrade to version 4.
Malformed frames fail as sanitized Apple queue-state integrity failures.

## Common

The in-memory provider preserves the exact plan across the same transition
matrix. Queue encoders and resolvers must preserve value equality for both
decision and plan. Platform persistence performs no strategy evaluation.
