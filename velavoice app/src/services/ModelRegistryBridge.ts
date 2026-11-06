/**
 * Module: src/services/ModelRegistryBridge
 * Intent: Map the native `ModelRegistry.ALL` JSON export (ticket 805, map
 * #290) into TS model definitions, with a clean null degradation when the
 * native bridge is unavailable. Pure — no expo/react-native module-scope
 * imports — so tsx tests and drift detection can exercise it directly.
 * Public API: NativeRegistryRow, ModelDefinitionFields,
 * registryRowToModelDefinition, getNativeRegistry.
 * Maintenance: Update this block when exports, invariants, side effects, or ownership change.
 */

/** Shape of one row of the JSON `ModelRegistry.ALL` export (ModelVerifier.getRegistry). */
export interface NativeRegistryRow {
  id: string;
  name: string;
  engine: 'WHISPER' | 'SHERPA';
  url: string;
  filename: string;
  expectedSha256: string | null;
  minRamMb: number;
  supportsStreaming: boolean;
  multilingual: boolean;
  languages: string[];
  downloadSizeHintMb: number;
}

/** Dependency-free definition fields every model needs (before db state merge). */
export interface ModelDefinitionFields {
  id: string;
  name: string;
  url: string;
  filename: string;
  expectedHash: string;
  minRamMb: number;
  engine: 'whisper' | 'sherpa' | 'cleaner';
  supportsStreaming: boolean;
  languages: string[];
  multilingual: boolean;
  downloadSizeHintMb: number;
  /**
   * False when the registry row has no pinned URL yet — the UI must render
   * the row as not-yet-downloadable instead of offering a broken download.
   */
  downloadAvailable: boolean;
}

/**
 * The native bundle is resolved lazily (and never at module scope) so this
 * module stays importable in offline tsx tests where react-native is absent.
 */
type NativeRegistryBridge = { getRegistry?: () => Promise<string> };
interface NativeBundle {
  NativeModules?: { ModelVerifier?: NativeRegistryBridge };
}
declare const require: (name: string) => NativeBundle;

function resolveModelVerifier(): NativeRegistryBridge | undefined {
  try {
    return require('react-native')?.NativeModules?.ModelVerifier;
  } catch {
    return undefined;
  }
}

/**
 * Maps one native registry JSON row into the definition shape.
 * Throws loudly on contract drift (missing/empty id) so regressions
 * between the Kotlin registry and this mapper cannot pass silently.
 */
export function registryRowToModelDefinition(row: NativeRegistryRow): ModelDefinitionFields {
  if (!row || typeof row.id !== 'string' || !row.id) {
    throw new Error('registry row missing id — Kotlin/TS contract drift detected');
  }
  const engine: ModelDefinitionFields['engine'] =
    row.engine === 'SHERPA' ? 'sherpa' : row.engine === 'WHISPER' ? 'whisper' : 'cleaner';
  return {
    id: row.id,
    name: row.name || row.id,
    url: row.url,
    filename: row.filename,
    expectedHash: row.expectedSha256 || '',
    minRamMb: row.minRamMb,
    engine,
    supportsStreaming: !!row.supportsStreaming,
    languages: Array.isArray(row.languages) ? row.languages : [],
    multilingual: !!row.multilingual,
    downloadSizeHintMb: row.downloadSizeHintMb,
    downloadAvailable: !!row.url,
  };
}

/** Cleanly returns null when the native registry bridge is unavailable (tsx tests, old native). */
export async function getNativeRegistry(): Promise<ModelDefinitionFields[] | null> {
  const verifierWithRegistry = resolveModelVerifier();
  if (!verifierWithRegistry || typeof verifierWithRegistry.getRegistry !== 'function') {
    return null;
  }
  try {
    const json = await verifierWithRegistry.getRegistry();
    const rows: NativeRegistryRow[] = JSON.parse(json);
    if (!Array.isArray(rows)) return null;
    return rows.map(registryRowToModelDefinition);
  } catch (e) {
    console.warn('ModelRegistry bridge unavailable, falling back to legacy list', e);
    return null;
  }
}
