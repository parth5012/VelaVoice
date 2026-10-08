// Single verdict authority (JS side) for the quarantine badge (map #130
// ticket #136): quarantined sessions stay visible library entries with an
// explicit local-only badge — hiding them would look like data loss. The flags
// cover both producers: native getRecentTranscriptions sends isQuarantined,
// JS library entries use quarantined, and correction-training payloads
// (api.ts) use privacySensitive/quarantined — all three route through this
// one predicate so payload and entry verdicts can never diverge. Pure + total
// so the badge can never crash a list render on null entries.
export interface QuarantinableEntry {
  isQuarantined?: boolean;
  quarantined?: boolean;
  privacySensitive?: boolean;
  isSynced?: boolean;
}

export const isQuarantinedEntry = (
  entry: QuarantinableEntry | null | undefined
): boolean =>
  entry?.isQuarantined === true ||
  entry?.quarantined === true ||
  entry?.privacySensitive === true;

export const quarantineBadgeText = (): string => 'Local-only — never uploads';
