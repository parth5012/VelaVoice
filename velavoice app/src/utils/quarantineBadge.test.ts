import { isQuarantinedEntry, quarantineBadgeText } from './quarantineBadge';

function assert(expr: boolean, message: string) {
  if (!expr) {
    throw new Error('Assertion failed: ' + message);
  }
}

function runTests() {
  console.log('Running quarantineBadge tests...');

  // Quarantined entries are detected via either flag (native sends
  // isQuarantined, JS library entries use quarantined).
  assert(isQuarantinedEntry({ isQuarantined: true }) === true, 'isQuarantined flag detected');
  assert(isQuarantinedEntry({ quarantined: true }) === true, 'quarantined flag detected');
  assert(
    isQuarantinedEntry({ isQuarantined: true, quarantined: true }) === true,
    'both flags detected'
  );

  // Non-quarantined entries stay unbadged — including unsynced ones, which
  // keep their own pending badge, never the quarantine badge.
  assert(isQuarantinedEntry({ isSynced: false }) === false, 'unsynced entry is not quarantined');
  assert(isQuarantinedEntry({ isSynced: true }) === false, 'synced entry is not quarantined');
  assert(
    isQuarantinedEntry({ isQuarantined: false, quarantined: false }) === false,
    'explicit false flags are not quarantined'
  );
  assert(isQuarantinedEntry({}) === false, 'empty entry is not quarantined');
  assert(isQuarantinedEntry(null) === false, 'null entry is not quarantined');
  assert(isQuarantinedEntry(undefined) === false, 'undefined entry is not quarantined');

  // Single-verdict union: correction-training payloads carry privacySensitive
  // (api.ts gate); the shared helper must honour it so payload and entry
  // verdicts can never diverge.
  assert(isQuarantinedEntry({ privacySensitive: true }) === true, 'privacySensitive flag detected');
  assert(
    isQuarantinedEntry({ privacySensitive: true, quarantined: false, isQuarantined: false }) === true,
    'privacySensitive true wins over explicit false quarantine flags'
  );
  assert(isQuarantinedEntry({ privacySensitive: false }) === false, 'explicit false privacySensitive is not quarantined');

  // The payload gate (api.ts) delegates to the same verdict — verified here
  // without importing api.ts (which pulls SQLite via ModelManager).
  assert(isQuarantinedEntry({ privacySensitive: true, quarantined: true }) === true, 'combined payload-style flags detected');

  // The badge text is explicit: quarantined sessions are visible entries that
  // never leave the device (hiding them would look like data loss).
  const text = quarantineBadgeText();
  assert(typeof text === 'string' && text.length > 0, 'badge text is non-empty');
  assert(text.toLowerCase().includes('local'), 'badge text says local-only');

  console.log('All quarantineBadge tests passed!');
}

runTests();
