import { ModelManager } from './ModelManager';

export interface SaveCorrectionPayload {
  audio_id: string;
  original_transcription: string;
  corrected_transcription: string;
  edits: any[];
  edit_distance: number;
  user_id?: string | null;
  confidence_score?: number | null;
  // Quarantine verdict (map #130 ticket #136): quarantined sessions are
  // local-only and must never enter the correction-training pipeline.
  privacySensitive?: boolean;
  quarantined?: boolean;
}

export const isQuarantinedPayload = (
  payload: SaveCorrectionPayload | null | undefined
): boolean =>
  payload?.privacySensitive === true || payload?.quarantined === true;

function requiredFieldError(payload: SaveCorrectionPayload, field: 'audio_id' | 'original_transcription' | 'corrected_transcription' | 'edits' | 'edit_distance'): string | null {
  const value = payload[field];
  if (field === 'edits') {
    if (!value) return 'edits list is required';
    return null;
  }
  if (field === 'audio_id') {
    if (!value) return 'audio_id is required';
    return null;
  }
  if (value === undefined || value === null) return `${field} is required`;
  return null;
}

export class CorrectionAPI {
  static async saveCorrection(payload: SaveCorrectionPayload): Promise<{ success: boolean; message?: string; error?: string }> {
    try {
      if (!payload) {
        return { success: false, error: 'payload is required' };
      }
      // Fail-closed quarantine gate: refuse before validation or SQLite so
      // quarantined content is never trainable. Counts only — never content.
      if (isQuarantinedPayload(payload)) {
        return { success: false, error: 'quarantined session is local-only and never saved for training' };
      }
      if (!payload.audio_id) {
        return { success: false, error: 'audio_id is required' };
      }
      for (const field of ['original_transcription', 'corrected_transcription', 'edits', 'edit_distance'] as const) {
        const error = requiredFieldError(payload, field);
        if (error) return { success: false, error };
      }

      const editsStr = JSON.stringify(payload.edits);
      await ModelManager.saveCorrection(
        payload.audio_id,
        payload.original_transcription,
        payload.corrected_transcription,
        editsStr,
        payload.edit_distance,
        payload.user_id,
        payload.confidence_score,
        isQuarantinedPayload(payload)
      );
      return { success: true, message: 'Correction saved successfully' };
    } catch (e: any) {
      console.error('Failed to save correction in API handler:', e);
      return { success: false, error: e.message || 'Database error occurred' };
    }
  }

  static async mockFetch(url: string, options?: RequestInit): Promise<Response> {
    if (url.endsWith('/save_correction') && options?.method === 'POST') {
      try {
        const payload: SaveCorrectionPayload = JSON.parse(options.body as string);
        const result = await this.saveCorrection(payload);
        return {
          ok: result.success,
          status: result.success ? 200 : 400,
          json: async () => result,
          text: async () => JSON.stringify(result),
        } as Response;
      } catch (e: any) {
        return {
          ok: false,
          status: 400,
          json: async () => ({ success: false, error: 'Invalid JSON request payload' }),
          text: async () => JSON.stringify({ success: false, error: 'Invalid JSON request payload' }),
        } as Response;
      }
    }
    
    // Fallback/pass-through
    return Promise.reject(new Error('Network request failed in mock environment'));
  }
}
