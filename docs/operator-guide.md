# Operator Guide

## First Deployment
1. Register operator, terminals/sites, satellite licenses/ITU-
   coordination filings, staff and robots.
2. Import existing terminal-provisioning and billing history.
3. Run read-only satellite-license-scope and terminal-installation
   robot mission dry-runs.
4. Configure safety-class allowed sets and human sign-off paths.
5. Publish a dry-run capacity-provisioning record and audit export.

## Minimum Production Controls
- satellite-license/ITU-coordination-scope validation before any
  capacity provisioning
- governor gate on every robot action before dispatch
- human sign-off for :high/:safety-critical actions (dish alignment
  near live RF emitters, tower-climb/high-voltage work)
- evidence-backed provisioning and suspension records
- audit export for every dispatch, sign-off, provisioning and
  suspension record
- backup manual network-operations process

## Certification
Certified operators must prove robot-safety integrity, satellite-
licensing/ITU-coordination discipline, evidence-backed provisioning
and suspension records, and human review for capacity-affecting
actions.
