# cloud-itonami-isic-6130

Open Business Blueprint for **ISIC Rev.5 6130**: satellite
telecommunications activities -- scoped specifically to a licensed
SATELLITE NETWORK OPERATOR providing VSAT/broadband-via-satellite and
satellite-phone connectivity: provisioning satellite terminals and
transponder/beam capacity for subscribers, run under a satellite
operating license and ITU frequency/orbital-slot coordination, not a
terrestrial numbering-plan or mobile-spectrum regime.

This repository publishes a satellite-operator actor -- subscriber
terminal intake, identity verification, ITU-coordination-dispute
screening, capacity provisioning and service suspension -- as an OSS
business that any qualified, licensed community satellite-network
operator can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet, most closely
[`cloud-itonami-isic-6190`](https://github.com/cloud-itonami/cloud-itonami-isic-6190)
(the sibling wired/VoIP-reseller telecom actor) and
[`cloud-itonami-isic-6120`](https://github.com/cloud-itonami/cloud-itonami-isic-6120)
(the sibling terrestrial mobile-network-operator actor) -- SAME ISIC
61xx telecommunications industry, distinct satellite-vs-wired-vs-
terrestrial-wireless scope. Here it is **Satellite Operations Advisor
⊣ Satellite Network Governor** (`:satellite-network-governor` in this
repo's own `blueprint.edn`).

> **Why an actor layer at all?** An LLM is great at drafting a
> terminal-intake summary, normalizing records, and checking whether a
> terminal's own recorded satellite number is even syntactically
> well-formed -- but it has **no notion of which jurisdiction's
> satellite-licensing/ITU-coordination requirements are official, no
> license to provision real satellite capacity or suspend a real
> subscriber's service, and no way to know on its own whether an ITU
> frequency-coordination or orbital-slot dispute against the
> terminal's site has actually stayed unresolved**. Letting it
> provision capacity or suspend service directly invites fabricated
> satellite-licensing citations, a terminal provisioned on a malformed
> satellite number, and an unresolved coordination dispute being
> quietly ignored -- and liability, and consumer-protection risk, for
> whoever runs it. This project seals the Satellite Operations Advisor
> into a single node and wraps it with an independent **Satellite
> Network Governor**, a human **approval workflow**, and an immutable
> **audit ledger**.

## Scope note: satellite operator, distinct from wired and terrestrial-wireless siblings

`cloud-itonami-isic-6190` ("Community Telecommunications Access") is
explicitly a VoIP/reseller/public-access business -- it holds no
spectrum or network infrastructure. `cloud-itonami-isic-6120`
("Community Mobile Network Infrastructure Operations") holds a
TERRESTRIAL mobile-spectrum license and operates cell towers/base
stations. This repository is deliberately scoped to the SEPARATE
business of holding a satellite operating license and coordinating
frequency/orbital-slot use through the ITU (Radio Regulations Article
9/11) to provide connectivity via a satellite constellation -- a
genuinely distinct, internationally-coordinated regulatory regime that
neither a terrestrial numbering-plan reseller nor a terrestrial
mobile-spectrum licensee is subject to. `cloud-itonami-isic-6110`
("Wired Telecommunications Network Operations") covers the wired/fiber
infrastructure side.

### What this actor does and does not do

This actor covers subscriber terminal intake through identity
verification, ITU-coordination-dispute screening, capacity
provisioning and service suspension. It does **not**, by itself, hold
any satellite operating license, orbital-slot assignment or ITU
coordination filing required to operate a satellite network in a given
jurisdiction, and it does not claim to. It also does **not** model a
real satellite payload/transponder telemetry system, a real ground-
station/gateway dispatch pipeline, or lawful-intercept infrastructure
-- no live transponder-occupancy monitoring, no real ground-station
command-and-control (see `satcom.facts`'s own docstring for the honest
simplification this makes: a starting catalog of satellite-licensing/
ITU-coordination authorities, not a survey of every jurisdiction's
variant). Whoever deploys and operates a live instance (a licensed
satellite-network operator) supplies the real satellite operating
license, the real ground-segment infrastructure and any real lawful-
intercept/emergency-path integrations, and bears that jurisdiction's
liability -- the software supplies the governed, spec-cited, audited
execution scaffold so that operator does not have to build the
compliance layer from scratch for every new market.

### Actuation

**Provisioning real satellite capacity or suspending a real
subscriber's service is never autonomous, at any phase, by
construction.** Two independent layers enforce this (`satcom.
governor`'s `:actuation/provision-capacity`/`:actuation/suspend-
service` high-stakes gate and `satcom.phase`'s phase table, which
never puts either op in any phase's `:auto` set) -- see `satcom.
phase`'s docstring and `test/satcom/phase_test.clj`'s
`provision-capacity-never-auto-at-any-phase`/`suspend-service-never-
auto-at-any-phase`. The actor may draft, check and recommend; a human
network operator is always the one who actually provisions capacity or
suspends service. Like `cloud-itonami-isic-6190`'s and
`cloud-itonami-isic-6120`'s own dual actuation, this actor has TWO
actuation events -- and like `6190`'s `:actuation/suppress-billing-
record` and `6120`'s `:actuation/suspend-service`, **`:actuation/
suspend-service` is a NEGATIVE actuation**: it withholds ongoing
connectivity rather than issuing a new record -- the FOURTH time this
fleet has modeled a high-stakes act in that direction (after
`cloud-itonami-isic-3600`'s alert suppression, `6190`'s billing-record
suppression and `6120`'s service suspension). See this actor's own
`docs/adr/0001-architecture.md` for the honest framing this makes.

**Real transponder/payload telemetry, ground-station command-and-
control, lawful-intercept, subscriber-location disclosure, and law-
enforcement-ordered service suspension are OUT OF SCOPE for this actor
by construction** -- there is no op, HARD check, or advisor dispatch
branch for any of them, mirroring `6120`'s own explicit "lawful-
intercept and emergency paths remain outside LLM control" posture. See
`docs/adr/0001-architecture.md`.

## The core contract

```
terminal intake + jurisdiction facts (satcom.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Satellite    │ ─────────────▶ │ Satellite                      │  (independent system)
   │ Operations   │  + citations    │ Network Governor:              │
   │ Advisor      │                 │ spec-basis · evidence-        │
   │ (sealed)     │                 │ incomplete · satellite-number-│
   └──────────────┘         commit ◀────┼──────────▶ hold │ format-invalid (structural) ·
                                 │             │           │ coordination-dispute-
                           record + ledger  escalate ─▶ human   unresolved (unconditional) ·
                                             (ALWAYS for         already-provisioned/-suspended
                                              :actuation/provision-
                                              capacity /
                                              :actuation/suspend-
                                              service)
```

**The Satellite Operations Advisor never provisions capacity or
suspends service the Satellite Network Governor would reject, and
never does so without a human sign-off.** Hard violations (fabricated
satellite-licensing/ITU-coordination requirements; unsupported
evidence; a malformed satellite number; an unresolved ITU-coordination
dispute; a double provisioning or suspension) force **hold** and
*cannot* be approved past; a clean provisioning/suspension proposal
still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean dual-actuation lifecycle + five HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (VSAT dish/terminal
installation, antenna alignment, remote-site maintenance) operate
under the actor, gated by the independent **Satellite Network
Governor**. The governor never dispatches hardware itself; `:high`/
`:safety-critical` actions (dish alignment near live RF emitters,
tower-climb or remote-site high-voltage work) require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Satellite Network Governor, capacity-provisioning + service-suspension draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6130`). Required capabilities are implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) -- missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/phone`](https://github.com/kotoba-lang/phone) -- E.164 numbering, SIP URIs, call/SMS records (shared with `cloud-itonami-isic-6190`/`6120`, same as `:robotics` is shared fleet-wide)

`satcom.*` cites these capability contracts for the shape of a real
satellite-number record/robot mission without requiring them directly,
the SAME "related capability contract but not required" posture
`telecom.*`/`wirelesstelecom.*`/`credit`/`leasing`/`card` established
-- the actor is fully self-contained and runs offline with `MemStore`;
a production deployment wires the real capabilities in as its
subscriber-management and robot-dispatch backends.

## Layout

| File | Role |
|---|---|
| `src/satcom/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + separate capacity-provisioning/service-suspension history. Both actuation ops act directly on a pre-seeded terminal, and the double-actuation guards check dedicated `:capacity-provisioned?`/`:service-suspended?` booleans rather than a `:status` value |
| `src/satcom/registry.cljc` | Capacity-provisioning + service-suspension draft records, plus `satellite-number-invalid-format?` (the THIRD application of this fleet's format/syntactic-validity check family, after `telecom.registry/e164-invalid-format?` [`6190`] and `wirelesstelecom.registry/msisdn-invalid-format?` [`6120`]) |
| `src/satcom/facts.cljc` | Per-jurisdiction satellite-licensing + ITU-coordination catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/satcom/satcomadvisor.cljc` | **Satellite Operations Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/verification/coordination-dispute-screening/capacity-provisioning/service-suspension proposals |
| `src/satcom/governor.cljc` | **Satellite Network Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · satellite-number-format-invalid, pure ground-truth structural recompute · coordination-dispute-unresolved, unconditional evaluation) + already-provisioned/already-suspended guards + 1 soft (confidence/actuation gate) |
| `src/satcom/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted verify → supervised (both capacity provisioning and service suspension always human; terminal intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/satcom/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/satcom/sim.cljc` | demo driver |
| `test/satcom/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers subscriber terminal intake through identity
verification, ITU-coordination-dispute screening, capacity
provisioning and service suspension -- the core governed lifecycle
this blueprint's own `docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Terminal intake + per-jurisdiction satellite-licensing/ITU-coordination checklisting, HARD-gated on an official spec-basis citation (`:terminal/intake`/`:identity/verify`) | Real transponder/payload telemetry, real ground-station command-and-control integration (see `satcom.facts`'s docstring) |
| ITU-coordination-dispute screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:coordination/screen`) | Real robot dispatch for VSAT terminal installation or maintenance |
| Capacity provisioning, HARD-gated on full evidence and satellite-number structural validity, plus a double-provisioning guard (`:actuation/provision-capacity`) | Lawful-intercept, subscriber-location disclosure, and law-enforcement-ordered suspension (deliberately outside LLM/actor control) |
| Service suspension, HARD-gated on full evidence and a double-suspension guard (`:actuation/suspend-service`) | |
| Immutable audit ledger for every intake/verification/screening/provisioning/suspension decision | |

Extending coverage is additive: add the next gate (e.g. a roaming/
handover-request check) as its own governed op with its own HARD
checks and tests, following the SAME "an independent governor
re-verifies against the actor's own records before any real-world act"
pattern this repo's flagship op already establishes.

## Jurisdiction coverage (honest)

`satcom.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `satcom.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `satcom.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to
make coverage look bigger.

## Maturity

`:implemented` -- `Satellite Operations Advisor` + `Satellite Network
Governor` run as real, tested code (see `Run` above), modeled closely
on `cloud-itonami-isic-6190`'s and `cloud-itonami-isic-6120`'s
architecture (the same ISIC 61xx telecommunications industry). See
`docs/adr/0001-architecture.md` for the history and design.

## License

AGPL-3.0-or-later.
