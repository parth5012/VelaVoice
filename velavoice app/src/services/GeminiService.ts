/**
 * Module: src/services/GeminiService
 * Intent: Google Gemini transcription + SecureStore key/model persistence boundary.
 * Responsibilities: API validation/transcription, pcmToWav/encodeBase64, payload builders, response parsing.
 * Public API: testGeminiApiKey, transcribeAudio, save/get/deleteGeminiApiKey, save/getGeminiModel, pcmToWav, encodeBase64, buildGeminiTranscriptionPayload, cleanTranscript, parseGeminiTranscriptionResponse
 * Invariants: Keys never logged; timeouts always cleared; empty audio rejected before network.
 * Side Effects: fetch to generativelanguage.googleapis.com, SecureStore/localStorage/native prefs I/O.
 * Maintenance: Update this block when exports, invariants, side effects, or ownership change.
 */
import * as SecureStore from 'expo-secure-store';
import { NativeModules } from 'react-native';

export const GEMINI_MODEL_LIVE = 'gemini-3.5-transcribe-live';
export const GEMINI_MODEL_BATCH = 'gemini-3.5-transcribe';
export const GEMINI_MODEL = GEMINI_MODEL_BATCH;
export const GEMINI_MODEL_STORAGE_KEY = 'vela_gemini_model';

export const SUPPORTED_GEMINI_MODELS = [
  {
    id: 'gemini-3.5-transcribe-live',
    name: 'Gemini 3.5 Transcribe Live (Sub-second Real-time)',
    description: 'Sub-second streaming endpoint for real-time speech-to-text (Gemini Live API)',
  },
  {
    id: 'gemini-3.5-transcribe',
    name: 'Gemini 3.5 Transcribe (Batch Full Audio)',
    description: 'Batch endpoint optimized for full audio upload, speaker identification & timestamps',
  },
  {
    id: 'gemini-2.0-flash',
    name: 'Gemini 2.0 Flash (Multimodal Audio)',
    description: 'Fast multimodal audio understanding with 15 RPM free tier',
  },
  {
    id: 'gemini-1.5-flash',
    name: 'Gemini 1.5 Flash (Legacy)',
    description: 'Stable production audio transcription',
  },
];

/**
 * Normalizes Gemini model ID:
 * - Maps "gemini-3.5" or "3.5" to "gemini-3.5-transcribe-live" (streaming) or "gemini-3.5-transcribe" (batch)
 * - Maps "gemini-3.6" or "3.6" to "gemini-3.5-transcribe-live" (streaming) or "gemini-3.5-transcribe" (batch)
 * - Maps "gemini-2.0" or "2.0" to "gemini-2.0-flash"
 * - Maps "gemini-1.5" or "1.5" to "gemini-1.5-flash"
 */
export function normalizeGeminiModel(model?: string, isStreaming: boolean = false): string {
  if (!model || model.trim() === '') {
    return isStreaming ? GEMINI_MODEL_LIVE : GEMINI_MODEL_BATCH;
  }
  const trimmed = model.trim();
  if (trimmed === 'gemini-3.5' || trimmed === '3.5' || trimmed === 'gemini-3.5-flash') {
    return isStreaming ? GEMINI_MODEL_LIVE : GEMINI_MODEL_BATCH;
  }
  if (trimmed === 'gemini-3.6' || trimmed === '3.6' || trimmed === 'gemini-3.6-flash') {
    return isStreaming ? GEMINI_MODEL_LIVE : GEMINI_MODEL_BATCH;
  }
  if (trimmed === 'gemini-2.0' || trimmed === '2.0') {
    return 'gemini-2.0-flash';
  }
  if (trimmed === 'gemini-1.5' || trimmed === '1.5') {
    return 'gemini-1.5-flash';
  }
  return trimmed;
}

export const sanitizeModel = normalizeGeminiModel;

export const GEMINI_API_STORAGE_KEY = 'vela_gemini_api_key';
export const GOOGLE_AI_STUDIO_URL = 'https://aistudio.google.com';
export const DEFAULT_SAMPLE_RATE = 16000;
export const DEFAULT_CHANNELS = 1;
export const DEFAULT_BITS_PER_SAMPLE = 16;
export const VERBATIM_SYSTEM_INSTRUCTION =
  'You are a professional verbatim speech-to-text transcriber. Output ONLY the exact spoken transcription of the audio. Do not add notes, explanations, commentary, or conversational replies.';

export interface GeminiValidationResult {
  success: boolean;
  error?: string;
}

export interface GeminiTranscriptionResult {
  success: boolean;
  text?: string;
  error?: string;
}

// Shared fetch/timeout/error helpers (Batch 1 DRY — preserves behavior, no API change)
export function toErrorMessage(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}

export function isTimeoutError(e: unknown): boolean {
  if (e instanceof Error) {
    if (e.name === 'AbortError') return true;
    const msg = e.message.toLowerCase();
    return msg.includes('timeout') || msg.includes('aborted');
  }
  return false;
}

export async function fetchWithTimeout(
  url: string,
  init: RequestInit,
  timeoutMs: number
): Promise<Response> {
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const timeoutId = controller ? setTimeout(() => controller.abort(), timeoutMs) : null;
  try {
    return await fetch(url, { ...init, signal: controller ? controller.signal : undefined });
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
  }
}

// In-memory fallback for environments where native SecureStore is unavailable
let inMemoryApiKey: string | null = null;
let inMemoryModel: string = GEMINI_MODEL;

/**
 * Validates a Google Gemini API key by making a lightweight test query
 * to the specified Gemini endpoint (defaults to gemini-3.6).
 *
 * Cleanly handles:
 * - 200 OK: Key is active and functional
 * - 429: Quota exceeded / rate limit with human-friendly message
 * - 400 / 403: Invalid API key with human-friendly message
 * - Network timeouts / aborts
 */
export async function testGeminiApiKey(
  apiKey: string,
  modelOrTimeout?: string | number,
  maybeTimeoutMs?: number
): Promise<GeminiValidationResult> {
  let model: string = GEMINI_MODEL;
  let timeoutMs: number = 10000;

  if (typeof modelOrTimeout === 'number') {
    timeoutMs = modelOrTimeout;
  } else if (typeof modelOrTimeout === 'string' && modelOrTimeout.trim()) {
    model = sanitizeModel(modelOrTimeout.trim());
    if (typeof maybeTimeoutMs === 'number') {
      timeoutMs = maybeTimeoutMs;
    }
  } else {
    model = sanitizeModel(model);
  }

  const trimmedKey = apiKey ? apiKey.trim() : '';
  if (!trimmedKey) {
    return {
      success: false,
      error: 'API key is required. Please provide a valid Gemini API key.',
    };
  }

  const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${encodeURIComponent(
    trimmedKey
  )}`;

  try {
    const response = await fetchWithTimeout(
      endpoint,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          contents: [
            {
              role: 'user',
              parts: [{ text: 'ping' }],
            },
          ],
          generationConfig: {
            maxOutputTokens: 1,
            temperature: 0.1,
          },
        }),
      },
      timeoutMs
    );

    if (response.ok) {
      return { success: true };
    }

    // Try parsing error body for diagnostics
    const errorBody = await response.json().catch(() => null);
    const serverMessage = errorBody?.error?.message;

    if (response.status === 404) {
      // 404-fallback decision (repo-quality audit 2026-10-06): auto-fallback
      // to gemini-2.0-flash is INTENTIONAL. AI Studio retires model aliases
      // without notice; a silent retry keeps preflight usable instead of
      // failing every user on a renamed default. The error surfaced when the
      // fallback also fails names the replacement explicitly.
      if (model.includes('3.5') || model.includes('3.6')) {
        const fallback = await testGeminiApiKey(trimmedKey, 'gemini-2.0-flash', timeoutMs);
        if (fallback.success) {
          return {
            success: true,
          };
        }
      }
      return {
        success: false,
        error: `Model '${model}' was not found in Google AI Studio. You can select 'gemini-2.0-flash' as an active alternative.`,
      };
    }

    if (response.status === 429) {
      return {
        success: false,
        error:
          'Quota exceeded (Rate limit). Please check your Gemini free tier quota in Google AI Studio and try again later.',
      };
    }

    if (response.status === 400 || response.status === 403) {
      return {
        success: false,
        error:
          'Invalid API key. Please check your Gemini API key in Google AI Studio and ensure it is entered correctly.',
      };
    }

    return {
      success: false,
      error: serverMessage
        ? `Gemini API error (${response.status}): ${serverMessage}`
        : `Gemini API returned status code ${response.status}. Please check your credentials and try again.`,
    };
  } catch (error: unknown) {
    if (isTimeoutError(error)) {
      return {
        success: false,
        error: 'Connection timed out while reaching Gemini API. Please check internet connection.',
      };
    }

    const msg = error instanceof Error && error.message ? error.message : null;
    return {
      success: false,
      error: msg
        ? `Network error: ${msg}`
        : 'Unable to reach Google Gemini API. Please check network connection.',
    };
  }
}

/**
 * Saves Gemini API key securely to platform storage using SecureStore with
 * fallback for web / testing.
 */
export async function saveGeminiApiKey(apiKey: string): Promise<void> {
  const trimmed = apiKey ? apiKey.trim() : '';
  
  // Sync to native Android SharedPreferences for VoiceAccessibilityService
  try {
    if (NativeModules?.ModelVerifier?.setStringPreference) {
      await NativeModules.ModelVerifier.setStringPreference('geminiApiKey', trimmed);
    }
  } catch (err) {
    // Non-fatal if native module not attached (e.g. testing / web)
  }

  try {
    const isAvailable = await SecureStore.isAvailableAsync().catch(() => false);
    if (isAvailable) {
      await SecureStore.setItemAsync(GEMINI_API_STORAGE_KEY, trimmed);
      return;
    }
  } catch {
    // Fall through to fallback storage
  }

  // Web fallback storage
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem(GEMINI_API_STORAGE_KEY, trimmed);
  } else {
    inMemoryApiKey = trimmed;
  }
}

/**
 * Retrieves Gemini API key from platform storage using SecureStore with
 * fallback for web / testing.
 */
export async function getGeminiApiKey(): Promise<string | null> {
  try {
    const isAvailable = await SecureStore.isAvailableAsync().catch(() => false);
    if (isAvailable) {
      const stored = await SecureStore.getItemAsync(GEMINI_API_STORAGE_KEY);
      if (stored !== null) {
        return stored;
      }
    }
  } catch {
    // Fall through to fallback storage
  }

  // Web fallback storage
  if (typeof localStorage !== 'undefined') {
    return localStorage.getItem(GEMINI_API_STORAGE_KEY);
  }

  return inMemoryApiKey;
}

/**
 * Deletes stored Gemini API key from platform storage using SecureStore.
 */
export async function deleteGeminiApiKey(): Promise<void> {
  // Clear native Android SharedPreferences
  try {
    if (NativeModules?.ModelVerifier?.setStringPreference) {
      await NativeModules.ModelVerifier.setStringPreference('geminiApiKey', '');
    }
  } catch (err) {
    // Non-fatal
  }

  try {
    const isAvailable = await SecureStore.isAvailableAsync().catch(() => false);
    if (isAvailable) {
      await SecureStore.deleteItemAsync(GEMINI_API_STORAGE_KEY);
    }
  } catch {
    // Fall through to fallback
  }

  if (typeof localStorage !== 'undefined') {
    localStorage.removeItem(GEMINI_API_STORAGE_KEY);
  } else {
    inMemoryApiKey = null;
  }
}

/**
 * Saves the selected Gemini model identifier to platform storage and native preferences.
 */
export async function saveGeminiModel(model: string): Promise<void> {
  const trimmed = sanitizeModel(model);

  // Sync to native Android SharedPreferences for VoiceAccessibilityService
  try {
    if (NativeModules?.ModelVerifier?.setStringPreference) {
      await NativeModules.ModelVerifier.setStringPreference('geminiModel', trimmed);
    }
  } catch (err) {
    // Non-fatal if native module not attached
  }

  try {
    const isAvailable = await SecureStore.isAvailableAsync().catch(() => false);
    if (isAvailable) {
      await SecureStore.setItemAsync(GEMINI_MODEL_STORAGE_KEY, trimmed);
      return;
    }
  } catch {
    // Fall through to fallback storage
  }

  if (typeof localStorage !== 'undefined') {
    localStorage.setItem(GEMINI_MODEL_STORAGE_KEY, trimmed);
  } else {
    inMemoryModel = trimmed;
  }
}

/**
 * Retrieves the selected Gemini model identifier from storage (sanitized).
 */
export async function getGeminiModel(): Promise<string> {
  try {
    const isAvailable = await SecureStore.isAvailableAsync().catch(() => false);
    if (isAvailable) {
      const stored = await SecureStore.getItemAsync(GEMINI_MODEL_STORAGE_KEY);
      if (stored) return sanitizeModel(stored);
    }
  } catch {
    // Fall through
  }

  if (typeof localStorage !== 'undefined') {
    const stored = localStorage.getItem(GEMINI_MODEL_STORAGE_KEY);
    if (stored) return sanitizeModel(stored);
  }

  return sanitizeModel(inMemoryModel);
}

/**
 * Converts raw 16-bit 16kHz mono PCM audio bytes to standard 44-byte WAV format.
 */
export function pcmToWav(
  pcmAudio: Uint8Array,
  sampleRate: number = DEFAULT_SAMPLE_RATE,
  channels: number = DEFAULT_CHANNELS,
  bitsPerSample: number = DEFAULT_BITS_PER_SAMPLE
): Uint8Array {
  const byteRate = Math.floor((sampleRate * channels * bitsPerSample) / 8);
  const blockAlign = Math.floor((channels * bitsPerSample) / 8);
  const dataSize = pcmAudio.length;
  const fileSize = 36 + dataSize;

  const wav = new Uint8Array(44 + dataSize);
  const view = new DataView(wav.buffer, wav.byteOffset, wav.byteLength);

  // 'RIFF' chunk descriptor
  wav[0] = 0x52; // 'R'
  wav[1] = 0x49; // 'I'
  wav[2] = 0x46; // 'F'
  wav[3] = 0x46; // 'F'
  view.setUint32(4, fileSize, true);
  wav[8] = 0x57;  // 'W'
  wav[9] = 0x41;  // 'A'
  wav[10] = 0x56; // 'V'
  wav[11] = 0x45; // 'E'

  // 'fmt ' sub-chunk
  wav[12] = 0x66; // 'f'
  wav[13] = 0x6d; // 'm'
  wav[14] = 0x74; // 't'
  wav[15] = 0x20; // ' '
  view.setUint32(16, 16, true); // Subchunk1Size (16 for PCM)
  view.setUint16(20, 1, true);  // AudioFormat: 1 = PCM
  view.setUint16(22, channels, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, byteRate, true);
  view.setUint16(32, blockAlign, true);
  view.setUint16(34, bitsPerSample, true);

  // 'data' sub-chunk
  wav[36] = 0x64; // 'd'
  wav[37] = 0x61; // 'a'
  wav[38] = 0x74; // 't'
  wav[39] = 0x61; // 'a'
  view.setUint32(40, dataSize, true);

  // Copy raw PCM data
  wav.set(pcmAudio, 44);

  return wav;
}

/**
 * Encodes Uint8Array bytes to Base64 string.
 */
export function encodeBase64(bytes: Uint8Array): string {
  if (typeof Buffer !== 'undefined') {
    return Buffer.from(bytes).toString('base64');
  }
  let binary = '';
  const len = bytes.byteLength;
  for (let i = 0; i < len; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

/**
 * Builds Gemini 2.0 Flash generateContent request body JSON object for verbatim STT.
 */
export function buildGeminiTranscriptionPayload(base64Audio: string) {
  return {
    system_instruction: {
      parts: [
        {
          text: VERBATIM_SYSTEM_INSTRUCTION,
        },
      ],
    },
    contents: [
      {
        role: 'user',
        parts: [
          {
            inline_data: {
              mime_type: 'audio/wav',
              data: base64Audio,
            },
          },
          {
            text: 'Transcribe this audio verbatim.',
          },
        ],
      },
    ],
    generationConfig: {
      temperature: 0.0,
    },
  };
}

/**
 * Cleans whitespace and markdown code fences (e.g. ```markdown ... ```) from Gemini transcript.
 */
export function cleanTranscript(raw: string): string {
  if (!raw) return '';
  let cleaned = raw.trim();
  if (cleaned.startsWith('```')) {
    cleaned = cleaned.replace(/^```[a-zA-Z0-9_-]*\r?\n?/, '');
  }
  if (cleaned.endsWith('```')) {
    cleaned = cleaned.replace(/\r?\n?```$/, '');
  }
  return cleaned.trim();
}

/**
 * Parses Gemini generateContent response and extracts cleaned transcript text.
 */
export function parseGeminiTranscriptionResponse(responseJson: unknown): string {
  if (!responseJson) return '';
  try {
    const data = typeof responseJson === 'string' ? JSON.parse(responseJson) : responseJson;
    const candidates = data?.candidates;
    if (!Array.isArray(candidates) || candidates.length === 0) {
      return '';
    }
    const parts = candidates[0]?.content?.parts;
    if (!Array.isArray(parts) || parts.length === 0) {
      return '';
    }
    const text = parts[0]?.text || '';
    return cleanTranscript(text);
  } catch {
    return '';
  }
}

/**
 * Transcribes 16kHz 16-bit mono PCM audio using Google Gemini REST API (default: gemini-3.6).
 */
export async function transcribeAudio(
  pcmAudio: Uint8Array,
  apiKey?: string,
  modelOrTimeout?: string | number,
  maybeTimeoutMs?: number
): Promise<GeminiTranscriptionResult> {
  let model: string = GEMINI_MODEL;
  let timeoutMs: number = 30000;

  if (typeof modelOrTimeout === 'number') {
    timeoutMs = modelOrTimeout;
  } else if (typeof modelOrTimeout === 'string' && modelOrTimeout.trim()) {
    model = modelOrTimeout.trim();
    if (typeof maybeTimeoutMs === 'number') {
      timeoutMs = maybeTimeoutMs;
    }
  } else {
    model = await getGeminiModel();
  }

  if (!pcmAudio || pcmAudio.length === 0) {
    return {
      success: false,
      error: 'Audio data is empty. Please provide a valid audio recording.',
    };
  }

  const keyToUse = (apiKey || (await getGeminiApiKey()) || '').trim();
  if (!keyToUse) {
    return {
      success: false,
      error: 'Invalid Gemini API key or unauthorized access.',
    };
  }

  const wavAudio = pcmToWav(pcmAudio);
  const base64Audio = encodeBase64(wavAudio);
  const payload = buildGeminiTranscriptionPayload(base64Audio);

  const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${encodeURIComponent(
    keyToUse
  )}`;

  try {
    const response = await fetchWithTimeout(
      endpoint,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      },
      timeoutMs
    );

    if (response.ok) {
      const responseData = await response.json();
      const text = parseGeminiTranscriptionResponse(responseData);
      return {
        success: true,
        text,
      };
    }

    const errorBody = await response.json().catch(() => null);
    const serverMessage = errorBody?.error?.message;

    if (response.status === 404) {
      // 404-fallback decision (repo-quality audit 2026-10-06): auto-fallback
      // to gemini-2.0-flash is INTENTIONAL — same rationale as the preflight
      // path in testGeminiApiKey. One retry only (guard below), so a missing
      // fallback model still errors with a clear replacement message.
      if (model !== 'gemini-2.0-flash') {
        return transcribeAudio(pcmAudio, keyToUse, 'gemini-2.0-flash', timeoutMs);
      }
      return {
        success: false,
        error: `Gemini model '${model}' was not found in Google AI Studio. Please use 'gemini-2.0-flash'.`,
      };
    }

    if (response.status === 429) {
      return {
        success: false,
        error:
          'Gemini rate limit exceeded. Free tier limit is 15 requests/minute. Please wait a moment.',
      };
    }

    if (response.status === 400 || response.status === 403) {
      return {
        success: false,
        error: 'Invalid Gemini API key or unauthorized access.',
      };
    }

    return {
      success: false,
      error: serverMessage
        ? `Gemini API error (${response.status}): ${serverMessage}`
        : `Gemini API returned status code ${response.status}. Please check your credentials and try again.`,
    };
  } catch (error: unknown) {
    if (isTimeoutError(error)) {
      return {
        success: false,
        error: 'Connection timed out while reaching Gemini API. Please check internet connection.',
      };
    }

    const msg = error instanceof Error && error.message ? error.message : null;
    return {
      success: false,
      error: msg
        ? `Network error: ${msg}`
        : 'Unable to reach Google Gemini API. Please check network connection.',
    };
  }
}

export const GeminiService = {
  testGeminiApiKey,
  saveGeminiApiKey,
  getGeminiApiKey,
  deleteGeminiApiKey,
  pcmToWav,
  encodeBase64,
  buildGeminiTranscriptionPayload,
  cleanTranscript,
  parseGeminiTranscriptionResponse,
  transcribeAudio,
  GEMINI_MODEL,
  GEMINI_API_STORAGE_KEY,
  GOOGLE_AI_STUDIO_URL,
  VERBATIM_SYSTEM_INSTRUCTION,
  DEFAULT_SAMPLE_RATE,
  DEFAULT_CHANNELS,
  DEFAULT_BITS_PER_SAMPLE,
};

export default GeminiService;
