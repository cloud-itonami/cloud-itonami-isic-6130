# ADR-0001: Satellite Operations Advisor ⊣ Satellite Network Governor architecture

## Status

Accepted. `cloud-itonami-isic-6130` published directly at
`:implemented` in the `kotoba-lang/industry` registry (no prior
`:blueprint`-only stage -- this repo, its docs, and its actor code all
land in the same build).

## Context

`cloud-itonami-isic-6130` publishes an OSS business blueprint for
community satellite telecommunications operations: satellite-license
and ITU frequency/orbital-slot coordination scope management,
subscriber terminal intake, capacity provisioning and service-
suspension records, run by a qualified, licensed satellite-network
operator. Unlike the last several promotions in this fleet, `"6130"`
had no prior blueprint repo to build on -- the only prior reference
(`gftdcojp/cloud-itonami-J6130`, the legacy pre-rename naming
convention) is a dead link (confirmed 404). This ADR records the
governed-actor architecture from a fresh scaffold, following the same
langgraph-clj StateGraph + independent Governor + Phase 0→3 rollout
pattern established by `cloud-itonami-isic-6511` (life insurance) and
applied most closely by `cloud-itonami-isic-6190` (wired/VoIP-reseller
telecom) and `cloud-itonami-isic-6120` (terrestrial mobile-network-
operator telecom) -- the two other actors in this fleet's ISIC 61xx
telecommunications division.

## Decision

### Decision 1: mirror `cloud-itonami-isic-6190`'s/`cloud-itonami-isic-6120`'s module shape, adapt the domain to a licensed satellite-network operator

This repo's `blueprint.edn`/`docs/business-model.md`/`docs/operator-
guide.md`/README scope `cloud-itonami-isic-6130` explicitly as a
licensed SATELLITE-NETWORK OPERATOR (provisioning VSAT/broadband-via-
satellite and satellite-phone terminals, coordinating frequency and
orbital-slot use through the ITU), distinct from `6190`'s VoIP/
reseller scope (no infrastructure or spectrum) and from `6120`'s
TERRESTRIAL mobile-spectrum-licensed scope (cell towers, base
stations). This build mirrors `telecom.*` (`6190`)/`wirelesstelecom.*`
(`6120`)'s MODULE SHAPE exactly (`facts`/`governor`/`operation`/
`phase`/`registry`/`sim`/`store`/`satcomadvisor`, one file each, the
same langgraph-clj StateGraph skeleton) -- the same "mirror the
sibling closest in industry, adapt the domain" move `6120`'s own
ADR-0001 documents it made against `6190`. The primary entity is a
`terminal` (a VSAT/satellite-terminal or satellite-phone subscriber
record), analogous to `telecom.store`'s `line` and `wirelesstelecom.
store`'s `line`.

### Decision 2: `:actuation/suspend-service` is this fleet's FOURTH negative actuation

Every actuation in this fleet prior to `cloud-itonami-isic-3600` was a
POSITIVE act: issuing or finalizing a real-world record. `3600`'s
`:actuation/suppress-alert`, `6190`'s `:actuation/suppress-billing-
record` and `6120`'s `:actuation/suspend-service` all broke that
pattern -- each WITHHOLDS/SILENCES something rather than issuing it.
This actor's own `:actuation/suspend-service` is the FOURTH negative
actuation in this fleet's history: it withholds ongoing satellite
connectivity (e.g. for non-payment or an unresolved coordination
violation) rather than issuing a new record. The governed-actor
discipline (HARD checks, high-stakes gate, phase-3 exclusion,
dedicated double-actuation boolean) generalizes cleanly to this fourth
negative instance with no special-casing required.

### Decision 3: entity and op shape

The primary entity is a `terminal` (a satellite subscriber's VSAT/
satellite-phone record, tied to a jurisdiction's satellite-licensing/
ITU-coordination scope). Five ops: `:terminal/intake` (directory
upsert, no capital risk), `:identity/verify` (per-jurisdiction
subscriber-identity + satellite-licensing evidence checklist, never
auto -- analogous to `telecom.operation`'s/`wirelesstelecom.
operation`'s `:identity/verify`), `:coordination/screen` (ITU
frequency-coordination/orbital-slot dispute screening on the
terminal's own site, unconditional-evaluation discipline, never auto
-- the satellite analog of `telecom.operation`'s `:billing/screen` and
`wirelesstelecom.operation`'s `:license/screen`), `:actuation/
provision-capacity` (POSITIVE, high-stakes), and `:actuation/suspend-
service` (NEGATIVE, high-stakes). This is the SAME dual-actuation-on-
one-entity shape `telecom` (`6190`), `wirelesstelecom` (`6120`) and
their own prior siblings all use.

### Decision 4: `satellite-number-invalid-format?` -- the THIRD application of this fleet's format/syntactic-validity check family

`satcom.registry/satellite-number-invalid-format?` independently
recomputes whether a terminal's own recorded satellite number is a
syntactically valid E.164-format number (leading `+`, no leading zero,
8-15 total digits). Real Global Mobile Satellite System (GMSS)
satellite-phone numbers (Inmarsat/Iridium-class, under ITU country
codes such as +870/+881, per ITU-T Recommendation E.164/E.168) ARE
themselves E.164-formatted, so this DELIBERATELY reuses the SAME check
shape `telecom.registry/e164-invalid-format?` (`6190`) established as
this fleet's first format/syntactic-validity check family and
`wirelesstelecom.registry/msisdn-invalid-format?` (`6120`) reused a
second time -- not a new "first instance" claim (that precedent
belongs to `6190`), but an honest THIRD application of the same family
to a genuinely different real-world identifier (a satellite terminal's
own assigned GMSS number, distinct from a fixed-line/VoIP number or a
terrestrial mobile MSISDN). It gates only `:actuation/provision-
capacity` (the point where a malformed satellite number would
otherwise get provisioned for real use), the same restricted-scope
placement `6190`'s/`6120`'s own format checks use.

### Decision 5: `coordination-dispute-unresolved-violations` -- the satellite ITU-coordination analog of `6190`'s billing-dispute and `6120`'s license-dispute checks

Following the discipline `casualty.governor/sanctions-violations`
established and every prior sibling's unconditional-evaluation
screening family applies (most directly `telecom.governor/billing-
dispute-unresolved-violations`, `6190`, and `wirelesstelecom.
governor/license-dispute-unresolved-violations`, `6120`),
`coordination-dispute-unresolved-violations` is evaluated
UNCONDITIONALLY -- not scoped to a specific op -- so `:coordination/
screen` itself can HARD-hold on its own finding, not merely gate the
downstream actuation. This models a genuinely distinct regulatory
concept from either sibling: an unresolved ITU Radio Regulations
Article 9/11 frequency-coordination or orbital-slot interference
dispute, an INTERNATIONALLY-coordinated process (not solely a national
regulator's numbering plan or terrestrial spectrum license). Exercised
in tests/demo via `:coordination/screen` DIRECTLY against an already-
flagged terminal, not via an actuation op against an unscreened
terminal -- the "screen the screening op directly, not the actuation
op" lesson `parksafety`'s ADR-2607071922 Decision 5 established, and
`6190`'s/`6120`'s own ADR-0001s most recently reaffirmed.

### Decision 6: dedicated double-actuation-guard booleans

`:capacity-provisioned?`/`:service-suspended?` are dedicated booleans
on the `terminal` record, never a single `:status` value -- the same
discipline every prior sibling governor's guards establish, informed
by `cloud-itonami-isic-6492`'s real status-lifecycle bug
(ADR-2607071320).

### Decision 7: Store protocol, MemStore + DatomicStore parity

`satcom.store/Store` is implemented by both `MemStore` (atom-backed,
default for dev/tests/demo) and `DatomicStore` (`langchain.db`-backed),
proven to satisfy the same contract in `test/satcom/
store_contract_test.clj` -- the same seam every sibling actor uses so
swapping the SSoT backend is a configuration change, not a rewrite.

### Decision 8: Phase 0→3 rollout

Phase 3's `:auto` set has exactly one member, `:terminal/intake` (no
capital risk). `:identity/verify` and `:coordination/screen` are never
auto-eligible at any phase (matching every sibling's screening-op
posture), and `:actuation/provision-capacity`/`:actuation/suspend-
service` are permanently excluded from every phase's `:auto` set -- a
structural fact, not a rollout milestone, enforced by BOTH `satcom.
phase` and `satcom.governor`'s `high-stakes` set independently.

### Decision 9: real transponder/payload telemetry, ground-station command-and-control, lawful-intercept and law-enforcement-ordered suspension are OUT OF SCOPE by construction, mirroring `6120`'s own posture

This repo's own already-published `docs/business-model.md` Trust
Controls do not name lawful-intercept, ground-station command-and-
control, or real payload telemetry as in scope, mirroring `6120`'s own
explicit "lawful-intercept and emergency paths remain outside LLM
control" precedent (itself mirroring `6190`'s). This actor follows the
SAME exclusion, not a new design: there is no op, HARD check, or
`satcom.satcomadvisor/infer` dispatch branch for disclosing subscriber
location, commanding ground-station/gateway hardware, or a law-
enforcement-ordered suspension distinct from the ordinary `:actuation/
suspend-service` (non-payment/coordination-violation) op. A production
deployment wires these regulated paths through its own dedicated
infrastructure and legal process, entirely outside this actor's LLM
advisor and governor.

### Decision 10: direct `:spec` -> `:implemented` promotion, no separate `:blueprint` stage

Unlike `6120` (which was first published `:blueprint`-only and
promoted to `:implemented` in a later build), `6130` had no existing
blueprint repo and no active development in flight when this build
started -- the registry's own `:repo` field pointed at a confirmed-
dead legacy placeholder (`gftdcojp/cloud-itonami-J6130`). This build
publishes the full blueprint scaffold (README/docs/community files/
`blueprint.edn`) AND the governed-actor implementation (`src`/`test`/
`deps.edn`/this ADR) together, landing directly at `:implemented` --
the SAME direct spec→implemented promotion precedent this fleet
already uses (e.g. `cloud-itonami-isic-4620`, `cloud-itonami-isic-
2910`, per ADR-2607131000's own note on this precedent), rather than
introducing an intermediate `:blueprint`-only commit with no actor
code.

## Alternatives considered

- **Modeling satellite orbital-slot longitude as the format-check
  ground truth (instead of a GMSS satellite-phone number).** Rejected:
  ITU-assigned GEO orbital slots are a discrete registry entry (the
  ITU Master International Frequency Register), not a simple numeric
  range -- a "numeric range" check against slot longitude would be
  LESS honest than the E.164-format reuse, which is genuinely accurate
  to real GMSS satellite-phone numbering (ITU-T E.164/E.168, country
  codes +870/+881).
- **Modeling ground-station command-and-control or law-enforcement-
  ordered suspension as governed actuations.** Rejected for the same
  reason `6120`'s own ADR-0001 rejected the analogous choice: this
  repo's own published Trust Controls, and `6120`'s explicit "lawful-
  intercept and emergency paths remain outside LLM control" precedent,
  place these classes of act outside the actor entirely.
- **A single actuation (provisioning only), treating service
  suspension as a lower-stakes administrative note.** Rejected: this
  repo's own `docs/business-model.md` Trust Controls state "capacity
  cannot be provisioned outside a terminal's verified satellite-
  license/ITU-coordination scope" and "billing records require
  verified usage evidence," the same posture `6190`'s/`6120`'s own
  ADR-0001s used to justify treating the negative actuation as
  high-stakes on equal footing with the positive one.
- **Publishing `:blueprint`-only first, deferring the actor
  implementation.** Rejected: this fleet's direct spec→implemented
  precedent (Decision 10) applies cleanly here since no independent
  blueprint-stage work was already in flight, and splitting the build
  would add a registry churn step with no benefit.

## Consequences

- Confirms the negative-actuation pattern generalizes a fourth time
  (water-safety alerting, wired-telecom billing, terrestrial-mobile
  service continuity, satellite service continuity), not a one-off
  quirk of any single domain.
- Confirms `6190`'s format/syntactic-validity check family
  (`e164-invalid-format?`) generalizes to a THIRD real-world identifier
  (a satellite GMSS terminal number) without modification to its check
  shape.
- `kotoba-lang/industry`'s `:spec` tier count decreases by one and
  `:implemented` increases by one directly (no `:blueprint` stage);
  ISIC Wave 0 (ADR-2607121000) advances by one class, closing the last
  class-level gap alongside `"6391"` (left untouched -- may be handled
  by a separate concurrent build).
