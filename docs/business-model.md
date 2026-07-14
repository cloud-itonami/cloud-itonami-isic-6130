# Business Model: Community Satellite Telecommunications Operations

## Classification
- Repository: `cloud-itonami-isic-6130`
- ISIC Rev.5: `6130` — satellite telecommunications activities (this
  repository: licensed satellite-network operator scope)
- Social impact: connectivity, digital inclusion, remote access,
  disaster resilience

## Customer
- independent/community satellite-network operators needing an
  auditable ITU-coordination-compliance platform
- resellers (including `cloud-itonami-isic-6190`'s own VoIP/reseller
  operators) needing wholesale satellite backhaul
- remote/rural sites, maritime/aviation operators and disaster-
  response programs needing connectivity where terrestrial networks
  (`cloud-itonami-isic-6110`/`6120`) do not reach
- regulators needing verifiable satellite-licensing and ITU-
  coordination-filing records
- programs that cannot accept closed, unauditable network-management
  platforms

## Offer
- satellite-license and ITU frequency/orbital-slot coordination scope
  management
- robotics-assisted VSAT terminal/dish installation and maintenance
- subscriber terminal provisioning and capacity-allocation records
- billing and usage records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per terminal/site
- support retainer with SLA
- terminal-installation robot integration and maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (dish alignment near live RF emitters,
  tower-climb or remote-site high-voltage work) require human sign-off
- capacity cannot be provisioned outside a terminal's verified
  satellite-license/ITU-coordination scope
- billing records require verified usage evidence
- subscriber and terminal-site data stays outside Git
