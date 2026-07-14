# Governance

`cloud-itonami-isic-6130` is an OSS open-business blueprint for
community satellite telecommunications operations, robotics-premised.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- a robot action the governor refuses is never dispatched to hardware.
- the Satellite Network Governor remains independent of the advisor.
- hard policy violations (fabricated satellite-licensing spec-basis,
  evidenceless capacity provisioning, an unresolved ITU-coordination
  dispute ignored) cannot be overridden by human approval.
- every provisioning, suspension, screening and disclosure path is
  auditable.
- subscriber, terminal and site data stays outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, robot-safety, ITU-
coordination readiness and data-flow review.

Certified operators can lose certification for:

- bypassing capacity-provisioning or coordination-dispute checks
- mishandling subscriber or terminal-site data
- misrepresenting certification status
- failing to respond to network-reliability or coordination incidents
- hiding material changes to customer-facing operation
