# Burlington Hydro (Ontario, Canada)

Burlington Hydro is the first targeted utility for Open Green Button.

## Provider

Green Button services for Burlington Hydro are provided by London Hydro.

## Information on File

Registration created by Raman Gupta (@rocketraman) using his own contact information.

## mTLS Status

Not checked by utility.

## Water Data

Not available.
London Hydro does not offer water usage through Green Button for any of the utilities it supports, even where the utility bills you for water.
Water is supported by the Green Button standard but is not part of Ontario's Green Button mandate, so utilities are not required to implement it (confirmed by London Hydro, October 2026).
Only electricity data is available for Burlington Hydro through Open Green Button.
See the [London Hydro provider notes](../providers/london-hydro.md).

Requesting the water function block (FB_11) at Burlington's authorize endpoint is rejected with `invalid_scope` (tested 2026-10-09), so do not add it to this utility's `defaultScope`.

## Bill Cost

Every other bill also charges two months of Halton Region water, and the feed folds that into the bill's total without a line item for it.
On those bills `billLastPeriod`, "New Charges This Period" and "Total Amount Due" are the whole bill, for example $502.29 on the June 19 2026 bill, of which $314.56 was water (confirmed against the PDF bill, 2026-10-30).
The itemized lines (time-of-use, delivery, regulatory, H.S.T. and the Ontario Electricity Rebate) add up to the electricity alone, and the H.S.T. line is 13% of those charges only.

So Burlington sets the `summaryCostFromItemizedCharges` quirk, and the Home Assistant integration prices each bill from its itemized charges rather than its total.
Other utilities operated by London Hydro that bill water may need the same quirk once checked against a bill.
