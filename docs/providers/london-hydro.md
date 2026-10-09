# London Hydro

London Hydro runs the Green Button service for itself and for several other Ontario utilities.

## Utilities

| Utility | Proxy id | Notes |
| ------------- | ------------- | ------------- |
| Burlington Hydro | `burlington_hydro` | [burlington-hydro.md](../utilities/burlington-hydro.md) |
| Elk Energy | `elk_energy` | [elk-energy.md](../utilities/elk-energy.md) |
| Enwin | `enwin` | [enwin.md](../utilities/enwin.md) |
| Festival Hydro | `festival_hydro` | [festival-hydro.md](../utilities/festival-hydro.md) |
| London Hydro | `london_hydro` | [london-hydro.md](../utilities/london-hydro.md) |
| Newmarket-Tay (NT) Power | `nt_power` | [nt-power.md](../utilities/nt-power.md) |
| Niagara Peninsula Energy | `npe` | [niagara-peninsula-energy.md](../utilities/niagara-peninsula-energy.md) |
| Oakville Hydro | `oakville_hydro` | [oakville-hydro.md](../utilities/oakville-hydro.md) |
| Oshawa Power | `oshawa_power` | [oshawa-power.md](../utilities/oshawa-power.md) |

Issues for these utilities are labeled [`provider:london-hydro`](https://github.com/rocketraman/open-green-button/issues?q=label%3Aprovider%3Alondon-hydro).

## Water Data

Not supported for any London Hydro utility.

London Hydro does not offer water usage through Green Button for any of the utilities it supports, including London Hydro itself, even where the utility bills its customers for water.
Water is supported by the Green Button standard but is not part of Ontario's Green Button mandate, so utilities are not required to implement it (confirmed by London Hydro, October 2026).
Only electricity data is available through Open Green Button for these utilities.

Do not add the water function block (FB_11) to the `defaultScope` of any of these utilities in `utilities.conf`.
Requesting it at Burlington Hydro's authorize endpoint was rejected with `invalid_scope` (tested 2026-10-09), and a rejected scope breaks authorization for every user of that utility.
