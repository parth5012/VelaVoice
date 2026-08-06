import { ModelManager } from './ModelManager';

export interface SaveCorrectionPayload {
  audio_id: string;
  original_transcription: string;
  corrected_transcription: string;
  edits: any[];
  edit_distance: number;
  user_id?: string | null;
  confidence_score?: number | null;
}

export class CorrectionAPI {
  static async saveCorrection(payload: SaveCorrectionPayload): Promise<{ success: boolean; message?: string; error?: string }> {
    try {
      if (!payload) {
        return { success: false, error: 'payload is required' };
      }
      if (!payload.audio_id) {
        return { success: false, error: 'audio_id is required' };
      }
      if (payload.original_transcription === undefined || payload.original_transcription === null) {
        return { success: false, error: 'original_transcription is required' };
      }
      if (payload.corrected_transcription === undefined || payload.corrected_transcription === null) {
        return { success: false, error: 'corrected_transcription is required' };
      }
      if (!payload.edits) {
        return { success: false, error: 'edits list is required' };
      }
      if (payload.edit_distance === undefined || payload.edit_distance === null) {
        return { success: false, error: 'edit_distance is required' };
      }

      const editsStr = JSON.stringify(payload.edits);
      await ModelManager.saveCorrection(
        payload.audio_id,
        payload.original_transcription,
        payload.corrected_transcription,
        editsStr,
        payload.edit_distance,
        payload.user_id,
        payload.confidence_score
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
