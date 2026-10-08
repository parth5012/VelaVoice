/**
 * Module: src/services/installHttpOverrides
 * Intent: One-time global fetch patch so correction-training POSTs never leave the device.
 * Responsibilities: Route /save_correction requests to CorrectionAPI.mockFetch (local SQLite).
 * Public API: installHttpOverrides()
 * Invariants: Idempotent — re-installs are no-ops once realFetch is captured.
 * Side Effects: Replaces global.fetch; forwarded calls hit real network otherwise.
 * Maintenance: Update this block when exports, invariants, side effects, or ownership change.
 */
import { CorrectionAPI } from './api';

export function installHttpOverrides(): void {
  if (typeof global === 'undefined') return;
  const g = global as { realFetch?: typeof fetch; fetch: typeof fetch };
  if (g.realFetch) return;
  g.realFetch = g.fetch;
  g.fetch = ((url: string, options?: RequestInit) => {
    if (url && url.includes('/save_correction')) {
      return CorrectionAPI.mockFetch(url, options);
    }
    return g.realFetch
      ? g.realFetch(url, options)
      : Promise.reject(new Error('Network offline'));
  }) as typeof fetch;
}
