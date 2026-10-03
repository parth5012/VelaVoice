// Single verdict authority (JS side) for the quarantine badge (map #130
// ticket #136): quarantined sessions stay visible library entries with an
// explicit local-only badge — hiding them would look like data loss. The two
// flags cover both producers: native getRecentTranscriptions sends
// isQuarantined, JS library entries use quarantined. Pure + total so the badge
// can never crash a list render on null entries.
export const isQuarantinedEntry = (entry) =>
  entry?.isQuarantined === true || entry?.quarantined === true;

export const quarantineBadgeText = () => 'Local-only — never uploads';
