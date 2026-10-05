import { ModelManager } from './ModelManager';
export const isQuarantinedPayload = (payload) => payload?.privacySensitive === true || payload?.quarantined === true;
function requiredFieldError(payload, field) {
    const value = payload[field];
    if (field === 'edits') {
        if (!value)
            return 'edits list is required';
        return null;
    }
    if (field === 'audio_id') {
        if (!value)
            return 'audio_id is required';
        return null;
    }
    if (value === undefined || value === null)
        return `${field} is required`;
    return null;
}
export class CorrectionAPI {
    static async saveCorrection(payload) {
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
            for (const field of ['original_transcription', 'corrected_transcription', 'edits', 'edit_distance']) {
                const error = requiredFieldError(payload, field);
                if (error)
                    return { success: false, error };
            }
            const editsStr = JSON.stringify(payload.edits);
            await ModelManager.saveCorrection(payload.audio_id, payload.original_transcription, payload.corrected_transcription, editsStr, payload.edit_distance, payload.user_id, payload.confidence_score, isQuarantinedPayload(payload));
            return { success: true, message: 'Correction saved successfully' };
        }
        catch (e) {
            console.error('Failed to save correction in API handler:', e);
            return { success: false, error: e.message || 'Database error occurred' };
        }
    }
    static async mockFetch(url, options) {
        if (url.endsWith('/save_correction') && options?.method === 'POST') {
            try {
                const payload = JSON.parse(options.body);
                const result = await this.saveCorrection(payload);
                return {
                    ok: result.success,
                    status: result.success ? 200 : 400,
                    json: async () => result,
                    text: async () => JSON.stringify(result),
                };
            }
            catch (e) {
                return {
                    ok: false,
                    status: 400,
                    json: async () => ({ success: false, error: 'Invalid JSON request payload' }),
                    text: async () => JSON.stringify({ success: false, error: 'Invalid JSON request payload' }),
                };
            }
        }
        // Fallback/pass-through
        return Promise.reject(new Error('Network request failed in mock environment'));
    }
}
