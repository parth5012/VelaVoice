/**
 * Module: src/components/engine/EngineRoomScreen
 * Intent: Engine Room tab — overlay status, transcription APIs, Drive sync, models, dictionary.
 * Responsibilities: Render settings forms and model management; all state lives in App (props-only screen).
 * Public API: EngineRoomScreen + EngineRoomScreenProps
 * Invariants: Pure presentation — no direct ModelManager/NativeModules calls; every action is a prop callback.
 * Side Effects: None locally (TextInput controlled by parent state).
 * Maintenance: Update this block when exports, invariants, side effects, or ownership change.
 */
import React from 'react';
import {
  View,
  Text,
  ScrollView,
  TextInput,
  TouchableOpacity,
  Switch,
  ActivityIndicator,
  StyleSheet,
} from 'react-native';
import { ModelInfo, DictionaryEntry, DictionaryKeyword } from '../../services/ModelManager';
import GeminiSettings from '../GeminiSettings';
import { isQuarantinedEntry, quarantineBadgeText } from '../../utils/quarantineBadge';
import { sharedScreenStyles } from '../../theme/appStyles';

export interface EngineRoomScreenProps {
  models: ModelInfo[];
  downloadProgress: { [key: string]: number };
  hasMicPermission: boolean;
  isAccessibilityEnabled: boolean;
  useLlmCleaner: boolean;
  transcriptionMode: string;
  streamingMode: string;
  groqApiKey: string;
  groqModel: string;
  openaiApiKey: string;
  openaiModel: string;
  openaiEndpoint: string;
  driveConfigured: boolean;
  isSyncing: boolean;
  syncResult: string | null;
  unsyncedCount: number;
  totalTranscriptions: number;
  // Typing pass (split plan pass 2+) should replace any[] with a native shape.
  recentTranscriptions: any[];
  dictionary: DictionaryEntry[];
  keywords: DictionaryKeyword[];
  keywordInput: string;
  originalWord: string;
  replacement: string;
  updatePreference: (key: string, value: string, setter: (val: string) => void) => void;
  setTranscriptionMode: (value: string) => void;
  setStreamingMode: (value: string) => void;
  setGroqApiKey: (value: string) => void;
  setGroqModel: (value: string) => void;
  setOpenaiApiKey: (value: string) => void;
  setOpenaiModel: (value: string) => void;
  setOpenaiEndpoint: (value: string) => void;
  requestMicPermission: () => Promise<boolean>;
  handleOpenAccessibilitySettings: () => void;
  handleToggleLlm: (value: boolean) => void;
  handleSyncToDrive: () => void;
  handleDownload: (id: string) => void;
  handleDelete: (id: string) => void;
  handleAddKeyword: () => void;
  handleDeleteKeyword: (id?: number) => void;
  handleAddEntry: () => void;
  handleDeleteEntry: (id?: number) => void;
  setKeywordInput: (value: string) => void;
  setOriginalWord: (value: string) => void;
  setReplacement: (value: string) => void;
  onGeminiApiKeyChange: (key: string) => void;
}

export const EngineRoomScreen: React.FC<EngineRoomScreenProps> = ({
  models,
  downloadProgress,
  hasMicPermission,
  isAccessibilityEnabled,
  useLlmCleaner,
  transcriptionMode,
  streamingMode,
  groqApiKey,
  groqModel,
  openaiApiKey,
  openaiModel,
  openaiEndpoint,
  driveConfigured,
  isSyncing,
  syncResult,
  unsyncedCount,
  totalTranscriptions,
  recentTranscriptions,
  dictionary,
  keywords,
  keywordInput,
  originalWord,
  replacement,
  updatePreference,
  setTranscriptionMode,
  setStreamingMode,
  setGroqApiKey,
  setGroqModel,
  setOpenaiApiKey,
  setOpenaiModel,
  setOpenaiEndpoint,
  requestMicPermission,
  handleOpenAccessibilitySettings,
  handleToggleLlm,
  handleSyncToDrive,
  handleDownload,
  handleDelete,
  handleAddKeyword,
  handleDeleteKeyword,
  handleAddEntry,
  handleDeleteEntry,
  setKeywordInput,
  setOriginalWord,
  setReplacement,
  onGeminiApiKeyChange,
}) => {
  const isLlamaDownloaded = models.some(m => m.id === 'cleaner-llama-3b' && m.status === 'completed');

  return (
    <ScrollView style={styles.tabContent} contentContainerStyle={{ paddingBottom: 40 }}>
      <Text style={styles.hubTitle}>The Engine Room</Text>
      <Text style={styles.studioSubtitle}>Configure computational models & parameters.</Text>

      {/* Accessibility & Mic Permission Config Card */}
      <View style={styles.engineCard}>
        <Text style={styles.engineCardTitle}>Floating Overlay Status</Text>

        <View style={styles.statusRow}>
          <Text style={styles.statusLabel}>Microphone Permission:</Text>
          <Text style={[styles.statusValue, hasMicPermission ? styles.statusActive : styles.statusInactive]}>
            {hasMicPermission ? 'GRANTED' : 'DENIED'}
          </Text>
        </View>

        <View style={styles.statusRow}>
          <Text style={styles.statusLabel}>Voice Overlay Service:</Text>
          <Text style={[styles.statusValue, isAccessibilityEnabled ? styles.statusActive : styles.statusInactive]}>
            {isAccessibilityEnabled ? 'ENABLED' : 'DISABLED'}
          </Text>
        </View>

        <View style={styles.engineActions}>
          {!hasMicPermission && (
            <TouchableOpacity style={styles.engineButton} onPress={requestMicPermission}>
              <Text style={styles.engineButtonText}>Grant Microphone Permission</Text>
            </TouchableOpacity>
          )}

          {hasMicPermission && !isAccessibilityEnabled && (
            <TouchableOpacity style={styles.engineButton} onPress={handleOpenAccessibilitySettings}>
              <Text style={styles.engineButtonText}>Enable Overlay Service (Accessibility)</Text>
            </TouchableOpacity>
          )}

        {hasMicPermission && isAccessibilityEnabled && (
          <View style={styles.engineStatusIndicator}>
            <Text style={styles.engineStatusIndicatorText}>✓ Floating Overlay Active & Ready</Text>
          </View>
        )}
      </View>
    </View>

    {/* Transcription API / Offline settings */}
      <View style={styles.engineCard}>
        <Text style={styles.engineCardTitle}>Transcription Engine</Text>
        <View style={styles.modeContainer}>
          <TouchableOpacity
            style={[styles.modeButton, transcriptionMode === 'local' && styles.modeButtonActive]}
            onPress={() => updatePreference('transcriptionMode', 'local', setTranscriptionMode)}
          >
            <Text style={[styles.modeButtonText, transcriptionMode === 'local' && styles.modeButtonTextActive]}>
              On-Device (Offline)
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.modeButton, transcriptionMode === 'groq' && styles.modeButtonActive]}
            onPress={() => updatePreference('transcriptionMode', 'groq', setTranscriptionMode)}
          >
            <Text style={[styles.modeButtonText, transcriptionMode === 'groq' && styles.modeButtonTextActive]}>
              Groq API
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.modeButton, transcriptionMode === 'openai' && styles.modeButtonActive]}
            onPress={() => updatePreference('transcriptionMode', 'openai', setTranscriptionMode)}
          >
            <Text style={[styles.modeButtonText, transcriptionMode === 'openai' && styles.modeButtonTextActive]}>
              OpenAI API
            </Text>
          </TouchableOpacity>
        <TouchableOpacity
          style={[styles.modeButton, transcriptionMode === 'gemini' && styles.modeButtonActive]}
          onPress={() => updatePreference('transcriptionMode', 'gemini', setTranscriptionMode)}
        >
                <Text style={[styles.modeButtonText, transcriptionMode === 'gemini' && styles.modeButtonTextActive]}>
                  Google Gemini 3.6
                </Text>
        </TouchableOpacity>
        </View>

        <Text style={styles.fieldLabel}>Streaming Mode</Text>
        <Text style={styles.dictionaryDescription}>
          Instant transcribes after you stop talking. Streamed types text live as you speak.
        </Text>
        <View style={styles.modeContainer}>
          <TouchableOpacity
            style={[styles.modeButton, streamingMode === 'instant' && styles.modeButtonActive]}
            onPress={() => updatePreference('streamingMode', 'instant', setStreamingMode)}
          >
            <Text style={[styles.modeButtonText, streamingMode === 'instant' && styles.modeButtonTextActive]}>
              Instant
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.modeButton, streamingMode === 'streamed' && styles.modeButtonActive]}
            onPress={() => updatePreference('streamingMode', 'streamed', setStreamingMode)}
          >
            <Text style={[styles.modeButtonText, streamingMode === 'streamed' && styles.modeButtonTextActive]}>
              Streamed
            </Text>
          </TouchableOpacity>
        </View>

        {transcriptionMode === 'groq' && (
          <View style={styles.apiFields}>
            <Text style={styles.fieldLabel}>Groq API Key</Text>
            <TextInput
              style={styles.inputField}
              placeholder="gsk_..."
              placeholderTextColor="#859491"
              value={groqApiKey}
              onChangeText={(val) => updatePreference('groqApiKey', val, setGroqApiKey)}
              secureTextEntry
            />
            <Text style={styles.fieldLabel}>Groq Model</Text>
            <TextInput
              style={styles.inputField}
              placeholder="whisper-large-v3"
              placeholderTextColor="#859491"
              value={groqModel}
              onChangeText={(val) => updatePreference('groqModel', val, setGroqModel)}
            />
          </View>
        )}

        {transcriptionMode === 'openai' && (
          <View style={styles.apiFields}>
            <Text style={styles.fieldLabel}>OpenAI API Key</Text>
            <TextInput
              style={styles.inputField}
              placeholder="sk-..."
              placeholderTextColor="#859491"
              value={openaiApiKey}
              onChangeText={(val) => updatePreference('openaiApiKey', val, setOpenaiApiKey)}
              secureTextEntry
            />
            <Text style={styles.fieldLabel}>API Endpoint URL</Text>
            <TextInput
              style={styles.inputField}
              placeholder="https://api.openai.com/v1"
              placeholderTextColor="#859491"
              value={openaiEndpoint}
              onChangeText={(val) => updatePreference('openaiEndpoint', val, setOpenaiEndpoint)}
            />
            <Text style={styles.fieldLabel}>OpenAI Model</Text>
            <TextInput
              style={styles.inputField}
              placeholder="whisper-1"
              placeholderTextColor="#859491"
              value={openaiModel}
              onChangeText={(val) => updatePreference('openaiModel', val, setOpenaiModel)}
            />
          </View>
        )}
      {transcriptionMode === 'gemini' && (
        <GeminiSettings
          currentMode={transcriptionMode}
          onSelectProvider={(mode) => updatePreference('transcriptionMode', mode, setTranscriptionMode)}
          onApiKeyChange={onGeminiApiKeyChange}
        />
      )}
      </View>

      {/* Google Drive Sync */}
        <View style={styles.engineCard}>
          <Text style={styles.engineCardTitle}>Google Drive Sync</Text>
          <Text style={styles.dictionaryDescription}>
            Automatically saves every transcription (raw + cleaned) to your device.
            Press "Sync with Drive" to upload all pending pairs to Google Drive.
          </Text>

          <View style={styles.statusRow}>
            <Text style={styles.statusLabel}>Drive Connection:</Text>
            <Text style={[styles.statusValue, driveConfigured ? styles.statusActive : styles.statusInactive]}>
              {driveConfigured ? 'CONFIGURED' : 'NOT SET UP'}
            </Text>
          </View>

          <View style={styles.statusRow}>
            <Text style={styles.statusLabel}>Local Transcriptions:</Text>
            <Text style={[styles.statusValue, { color: '#bacac7' }]}>
              {totalTranscriptions} total
            </Text>
          </View>

          {driveConfigured && (
            <View style={styles.statusRow}>
              <Text style={styles.statusLabel}>Pending Sync:</Text>
              <Text style={[styles.statusValue, unsyncedCount > 0 ? { color: '#ffb4ab' } : { color: '#28a745' }]}>
                {unsyncedCount > 0 ? `${unsyncedCount} pending` : 'All synced'}
              </Text>
            </View>
          )}

          {syncResult && (
            <View style={[styles.sandboxResult, { marginTop: 10, marginBottom: 10 }]}>
              <Text style={[styles.sandboxResultText, { fontSize: 13 }]}>{syncResult}</Text>
            </View>
          )}

        <View style={styles.engineActions}>
          {driveConfigured && (
            <TouchableOpacity
              style={[styles.engineButton, isSyncing && { opacity: 0.5 }]}
              onPress={handleSyncToDrive}
              disabled={isSyncing}
            >
              <Text style={styles.engineButtonText}>
                {isSyncing ? '⏳ Syncing...' : `☁️ Sync with Drive (${unsyncedCount})`}
              </Text>
            </TouchableOpacity>
          )}
        </View>
      </View>

      {/* Recent Transcriptions log/viewer */}
      <View style={styles.engineCard}>
        <Text style={styles.engineCardTitle}>Recent Transcriptions</Text>
        <Text style={styles.dictionaryDescription}>
          The latest transcription logs saved on your device and their Google Drive sync status.
        </Text>
        {recentTranscriptions.length === 0 ? (
          <Text style={styles.emptyDictText}>No recent transcriptions found.</Text>
        ) : (
          <View style={styles.transcriptionList}>
            {recentTranscriptions.map((item, index) => (
              <View key={index} style={styles.transcriptionItem}>
                <View style={styles.transcriptionHeader}>
                  <Text style={styles.transcriptionDate}>
                    {item.createdAt ? new Date(item.createdAt).toLocaleString() : item.fileName.replace('.json', '').replace(/_/g, ' ')}
                  </Text>
                  {isQuarantinedEntry(item) ? (
                    <View style={[
                      styles.syncBadge,
                      styles.quarantineBadge
                    ]}>
                      <Text style={styles.syncBadgeText}>
                        🔒 {quarantineBadgeText()}
                      </Text>
                    </View>
                  ) : (
                  <View style={[
                    styles.syncBadge,
                    item.isSynced ? styles.syncBadgeSuccess : styles.syncBadgePending
                  ]}>
                    <Text style={styles.syncBadgeText}>
                      {item.isSynced ? 'Synced' : 'Local Only'}
                    </Text>
                  </View>
                  )}
                </View>

                <Text style={styles.transcriptLabel}>Raw Text:</Text>
                <Text style={styles.engineTranscriptText}>{item.raw}</Text>

                {item.cleaned && item.cleaned !== item.raw ? (
                  <>
                    <Text style={styles.transcriptLabel}>Cleaned Text:</Text>
                    <Text style={styles.engineTranscriptTextCleaned}>{item.cleaned}</Text>
                  </>
                ) : null}

                <Text style={styles.engineTranscriptMeta}>
                  Duration: {Math.round(item.durationMs / 1000)}s | File: {item.fileName}
                </Text>
              </View>
            ))}
          </View>
        )}
      </View>

      {/* LLM Cleaner toggle */}
        <View style={styles.engineCard}>
          <View style={styles.cleanerHeader}>
            <Text style={styles.engineCardTitle}>LLM Cleaner Model (Llama 1B)</Text>
            <Switch
              value={useLlmCleaner}
              onValueChange={handleToggleLlm}
              disabled={!isLlamaDownloaded}
              trackColor={{ false: '#3c4948', true: '#62f9ee' }}
              thumbColor={useLlmCleaner ? '#ffffff' : '#859491'}
            />
          </View>

          {!isLlamaDownloaded ? (
            <Text style={styles.disabledText}>
              Download the Llama cleaner model below to enable advanced grammatical post-transcription cleaning.
            </Text>
          ) : (
            <Text style={styles.enabledText}>
              Advanced offline LLM cleaner is active. The overlay service will refine raw transcripts automatically.
            </Text>
          )}
        </View>

        {/* Local Models List */}
        <View style={styles.engineCard}>
          <Text style={styles.engineCardTitle}>On-Device Model Files</Text>
          {models.map((item) => {
            const progress = downloadProgress[item.id] || 0;
            const progressPercent = Math.round(progress * 100);

            return (
              <View key={item.id} style={styles.modelItem}>
                <View style={styles.modelItemHeader}>
                  <View>
                    <Text style={styles.modelItemName}>{item.name}</Text>
                    <Text style={styles.modelItemFile}>{item.filename}</Text>
                  </View>
                  <Text style={[styles.modelStatusTag, styles[item.status]]}>
                    {item.status.toUpperCase()}
                  </Text>
                </View>

                {item.status === 'downloading' && (
                  <View style={styles.progressContainer}>
                    <View style={styles.progressBarBg}>
                      <View style={[styles.progressBarFill, { width: `${progressPercent}%` }]} />
                    </View>
                    <Text style={styles.progressText}>{progressPercent}%</Text>
                  </View>
                )}

                <View style={styles.modelActions}>
                  {(item.status === 'pending' || item.status === 'failed' || item.status === 'checksum_failed') && (
                    <TouchableOpacity style={styles.downloadBtn} onPress={() => handleDownload(item.id)}>
                      <Text style={styles.downloadBtnText}>Download Model</Text>
                    </TouchableOpacity>
                  )}

                  {item.status === 'completed' && (
                    <TouchableOpacity style={styles.deleteBtn} onPress={() => handleDelete(item.id)}>
                      <Text style={styles.deleteBtnText}>Delete</Text>
                    </TouchableOpacity>
                  )}

                  {item.status === 'downloading' && (
                    <ActivityIndicator size="small" color="#62f9ee" />
                  )}
                </View>
              </View>
            );
          })}
        </View>

        {/* Dictionary Keywords */}
        <View style={styles.engineCard}>
          <Text style={styles.engineCardTitle}>Dictionary Keywords</Text>
          <Text style={styles.dictionaryDescription}>
            Add keywords, names, and technical terms so Whisper recognizes them correctly during transcription. Like Willow Voice's dictionary terms.
          </Text>

          <View style={styles.formContainer}>
            <View style={styles.keywordInputRow}>
              <TextInput
                style={styles.keywordInputField}
                placeholder="Enter a keyword (e.g. Kubernetes)"
                placeholderTextColor="#859491"
                value={keywordInput}
                onChangeText={setKeywordInput}
                onSubmitEditing={handleAddKeyword}
                returnKeyType="done"
              />
              <TouchableOpacity style={styles.keywordAddButton} onPress={handleAddKeyword}>
                <Text style={styles.keywordAddButtonText}>+</Text>
              </TouchableOpacity>
            </View>
          </View>

          {keywords.length > 0 ? (
            <View style={styles.dictionaryList}>
              <Text style={styles.listHeader}>Keywords ({keywords.length}):</Text>
              <View style={styles.keywordChipContainer}>
                {keywords.map((kw) => (
                  <View key={kw.id} style={styles.keywordChip}>
                    <Text style={styles.keywordChipText}>{kw.keyword}</Text>
                    <TouchableOpacity
                      style={styles.keywordChipDelete}
                      onPress={() => handleDeleteKeyword(kw.id)}
                    >
                      <Text style={styles.keywordChipDeleteText}>✕</Text>
                    </TouchableOpacity>
                  </View>
                ))}
              </View>
            </View>
          ) : (
            <Text style={styles.emptyDictText}>No keywords added yet. Add terms you use frequently.</Text>
          )}

          <View style={styles.keywordTip}>
            <Text style={styles.keywordTipIcon}>💡</Text>
            <Text style={styles.keywordTipText}>
              Tip: Adding product names, acronyms, and jargon improves transcription accuracy — similar to Willow Voice's custom dictionary.
            </Text>
          </View>
        </View>

        {/* Personal Dictionary */}
        <View style={styles.engineCard}>
          <Text style={styles.engineCardTitle}>Personal Dictionary (Corrections)</Text>
          <Text style={styles.dictionaryDescription}>
            Define on-device custom replacements (e.g. names, spellings) for Whisper outputs.
          </Text>

          <View style={styles.formContainer}>
            <TextInput
              style={styles.inputField}
              placeholder="Original word (e.g. Vela)"
              placeholderTextColor="#859491"
              value={originalWord}
              onChangeText={setOriginalWord}
            />
            <TextInput
              style={styles.inputField}
              placeholder="Replacement word (e.g. VELA)"
              placeholderTextColor="#859491"
              value={replacement}
              onChangeText={setReplacement}
            />
            <TouchableOpacity style={styles.addButton} onPress={handleAddEntry}>
              <Text style={styles.addButtonText}>Add Mapping</Text>
            </TouchableOpacity>
          </View>

          {dictionary.length > 0 ? (
            <View style={styles.dictionaryList}>
              <Text style={styles.listHeader}>Current Mappings ({dictionary.length}):</Text>
              {dictionary.map((entry) => (
                <View key={entry.id} style={styles.dictionaryRow}>
                  <View style={styles.dictionaryTextContainer}>
                    <Text style={styles.originalWordText}>{entry.original_word}</Text>
                    <Text style={styles.arrowText}>➔</Text>
                    <Text style={styles.replacementText}>{entry.replacement}</Text>
                  </View>
                  <TouchableOpacity
                    style={styles.rowDeleteBtn}
                    onPress={() => handleDeleteEntry(entry.id)}
                  >
                    <Text style={styles.rowDeleteBtnText}>✕</Text>
                  </TouchableOpacity>
                </View>
              ))}
            </View>
          ) : (
            <Text style={styles.emptyDictText}>No custom mappings added yet.</Text>
          )}
        </View>
    </ScrollView>
  );
};

const styles = {
  // Tab-level primitives (tabContent/hubTitle/studioSubtitle/sandboxResult*)
  // come from the shared theme module; everything below is engine-local.
  ...sharedScreenStyles,
  ...StyleSheet.create({
    engineCard: {
      backgroundColor: '#161d1c',
      borderRadius: 12,
      padding: 16,
      marginBottom: 16,
      borderWidth: 1,
      borderColor: '#3c4948',
    },
    engineCardTitle: {
      fontSize: 17,
      fontWeight: 'bold',
      color: '#ffffff',
      marginBottom: 12,
    },
    statusRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: 10,
    },
    statusLabel: {
      fontSize: 14,
      color: '#bacac7',
    },
    statusValue: {
      fontSize: 13,
      fontWeight: 'bold',
    },
    statusActive: {
      color: '#28a745',
    },
    statusInactive: {
      color: '#ffb4ab',
    },
    engineActions: {
      marginTop: 10,
    },
    engineButton: {
      backgroundColor: '#1a2120',
      paddingVertical: 10,
      borderRadius: 8,
      alignItems: 'center',
      borderWidth: 1,
      borderColor: '#3c4948',
      marginBottom: 8,
    },
    engineButtonText: {
      color: '#62f9ee',
      fontWeight: 'bold',
      fontSize: 13,
    },
    engineStatusIndicator: {
      backgroundColor: '#1a2120',
      paddingVertical: 10,
      borderRadius: 8,
      alignItems: 'center',
      borderWidth: 1,
      borderColor: '#3c4948',
    },
    engineStatusIndicatorText: {
      color: '#62f9ee',
      fontWeight: 'bold',
      fontSize: 13,
    },
    cleanerHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
    },
    transcriptionList: {
      marginTop: 10,
    },
    transcriptionItem: {
      backgroundColor: '#0a0f0e',
      borderRadius: 8,
      padding: 12,
      marginBottom: 12,
      borderWidth: 1,
      borderColor: '#3c4948',
    },
    transcriptionHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: 8,
      borderBottomWidth: 1,
      borderBottomColor: '#202928',
      paddingBottom: 6,
    },
    transcriptionDate: {
      fontSize: 12,
      fontWeight: 'bold',
      color: '#dde4e2',
    },
    syncBadge: {
      paddingHorizontal: 6,
      paddingVertical: 2,
      borderRadius: 4,
    },
    syncBadgeSuccess: {
      backgroundColor: '#00504b',
    },
    syncBadgePending: {
      backgroundColor: '#3c1800',
    },
    syncBadgeText: {
      fontSize: 10,
      fontWeight: 'bold',
      color: '#ffffff',
    },
    // Quarantined sessions are visible entries that never sync (ticket #136):
    // deep-red badge, distinct from the pending-sync amber.
    quarantineBadge: {
      backgroundColor: '#5c1a1a',
      borderColor: '#ff6b6b',
      borderWidth: 1,
    },
    transcriptLabel: {
      fontSize: 11,
      fontWeight: 'bold',
      color: '#859491',
      marginTop: 4,
    },
    engineTranscriptText: {
      fontSize: 13,
      color: '#dde4e2',
      lineHeight: 18,
      marginBottom: 4,
    },
    engineTranscriptTextCleaned: {
      fontSize: 13,
      color: '#62f9ee',
      lineHeight: 18,
      marginBottom: 4,
    },
    engineTranscriptMeta: {
      fontSize: 10,
      color: '#859491',
      marginTop: 6,
      borderTopWidth: 1,
      borderTopColor: '#202928',
      paddingTop: 4,
    },
    disabledText: {
      fontSize: 12,
      color: '#859491',
      lineHeight: 18,
      marginTop: 8,
    },
    enabledText: {
      fontSize: 12,
      color: '#62f9ee',
      lineHeight: 18,
      marginTop: 8,
    },
    modelItem: {
      borderBottomWidth: 1,
      borderBottomColor: '#3c4948',
      paddingVertical: 12,
    },
    modelItemHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'flex-start',
    },
    modelItemName: {
      fontSize: 15,
      fontWeight: 'bold',
      color: '#ffffff',
    },
    modelItemFile: {
      fontSize: 11,
      color: '#859491',
      marginTop: 2,
    },
    modelStatusTag: {
      fontSize: 9,
      fontWeight: 'bold',
      paddingVertical: 2,
      paddingHorizontal: 6,
      borderRadius: 4,
      overflow: 'hidden',
    },
    pending: {
      backgroundColor: '#2f3635',
      color: '#859491',
    },
    downloading: {
      backgroundColor: '#00504b',
      color: '#62f9ee',
    },
    completed: {
      backgroundColor: '#161d1c',
      color: '#28a745',
      borderWidth: 1,
      borderColor: '#28a745',
    },
    failed: {
      backgroundColor: '#93000a',
      color: '#ffb4ab',
    },
    checksum_failed: {
      backgroundColor: '#93000a',
      color: '#ffb4ab',
    },
    progressContainer: {
      flexDirection: 'row',
      alignItems: 'center',
      marginTop: 10,
    },
    progressBarBg: {
      flex: 1,
      backgroundColor: '#0e1514',
      borderRadius: 4,
      height: 6,
      overflow: 'hidden',
    },
    progressBarFill: {
      height: '100%',
      backgroundColor: '#62f9ee',
    },
    progressText: {
      color: '#dde4e2',
      fontSize: 11,
      width: 35,
      textAlign: 'right',
    },
    modelActions: {
      flexDirection: 'row',
      justifyContent: 'flex-end',
      marginTop: 8,
    },
    downloadBtn: {
      backgroundColor: '#1a2120',
      paddingVertical: 4,
      paddingHorizontal: 10,
      borderRadius: 4,
      borderWidth: 1,
      borderColor: '#3c4948',
    },
    downloadBtnText: {
      color: '#62f9ee',
      fontSize: 11,
      fontWeight: 'bold',
    },
    deleteBtn: {
      backgroundColor: '#93000a',
      paddingVertical: 4,
      paddingHorizontal: 10,
      borderRadius: 4,
    },
    deleteBtnText: {
      color: '#ffb4ab',
      fontSize: 11,
      fontWeight: 'bold',
    },
    dictionaryDescription: {
      fontSize: 12,
      color: '#859491',
      lineHeight: 18,
      marginBottom: 12,
    },
    formContainer: {
      marginBottom: 12,
    },
    inputField: {
      backgroundColor: '#0e1514',
      borderRadius: 8,
      borderWidth: 1,
      borderColor: '#3c4948',
      padding: 10,
      color: '#dde4e2',
      fontSize: 14,
      marginBottom: 10,
    },
    addButton: {
      backgroundColor: '#62f9ee',
      paddingVertical: 10,
      borderRadius: 8,
      alignItems: 'center',
    },
    addButtonText: {
      color: '#003734',
      fontWeight: 'bold',
      fontSize: 14,
    },
    dictionaryList: {
      marginTop: 10,
      borderTopWidth: 1,
      borderTopColor: '#3c4948',
      paddingTop: 10,
    },
    listHeader: {
      fontSize: 13,
      fontWeight: 'bold',
      color: '#bacac7',
      marginBottom: 8,
    },
    dictionaryRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingVertical: 6,
      borderBottomWidth: 1,
      borderBottomColor: '#1a2120',
    },
    dictionaryTextContainer: {
      flexDirection: 'row',
      alignItems: 'center',
      flex: 1,
    },
    originalWordText: {
      fontSize: 14,
      fontWeight: 'bold',
      color: '#ffffff',
    },
    arrowText: {
      fontSize: 14,
      color: '#859491',
      marginHorizontal: 8,
    },
    replacementText: {
      fontSize: 14,
      color: '#62f9ee',
    },
    rowDeleteBtn: {
      padding: 6,
    },
    rowDeleteBtnText: {
      color: '#ffb4ab',
      fontSize: 14,
    },
    emptyDictText: {
      fontSize: 12,
      color: '#859491',
      textAlign: 'center',
      marginTop: 10,
    },
    keywordInputRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 8,
    },
    keywordInputField: {
      flex: 1,
      backgroundColor: '#0e1514',
      borderRadius: 8,
      borderWidth: 1,
      borderColor: '#3c4948',
      padding: 10,
      color: '#dde4e2',
      fontSize: 14,
    },
    keywordAddButton: {
      width: 40,
      height: 40,
      backgroundColor: '#62f9ee',
      borderRadius: 20,
      justifyContent: 'center',
      alignItems: 'center',
    },
    keywordAddButtonText: {
      color: '#003734',
      fontSize: 20,
      fontWeight: 'bold',
      lineHeight: 22,
    },
    keywordChipContainer: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: 8,
      marginTop: 4,
    },
    keywordChip: {
      flexDirection: 'row',
      alignItems: 'center',
      backgroundColor: '#622599',
      borderRadius: 16,
      paddingVertical: 6,
      paddingHorizontal: 12,
      borderWidth: 1,
      borderColor: '#ddb7ff',
    },
    keywordChipText: {
      color: '#ffffff',
      fontSize: 13,
      fontWeight: '500',
      marginRight: 6,
    },
    keywordChipDelete: {
      width: 18,
      height: 18,
      borderRadius: 9,
      backgroundColor: 'rgba(255,255,255,0.2)',
      justifyContent: 'center',
      alignItems: 'center',
    },
    keywordChipDeleteText: {
      color: '#ffffff',
      fontSize: 10,
      fontWeight: 'bold',
    },
    keywordTip: {
      flexDirection: 'row',
      alignItems: 'center',
      marginTop: 14,
      padding: 10,
      backgroundColor: '#1a2120',
      borderRadius: 8,
      borderWidth: 1,
      borderColor: '#3c4948',
      gap: 8,
    },
    keywordTipIcon: {
      fontSize: 16,
    },
    keywordTipText: {
      flex: 1,
      fontSize: 12,
      color: '#bacac7',
      lineHeight: 16,
    },
    modeContainer: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      marginBottom: 12,
    },
    modeButton: {
      flex: 1,
      backgroundColor: '#0e1514',
      paddingVertical: 10,
      borderRadius: 8,
      borderWidth: 1,
      borderColor: '#3c4948',
      alignItems: 'center',
      marginHorizontal: 4,
    },
    modeButtonActive: {
      backgroundColor: '#62f9ee',
      borderColor: '#62f9ee',
    },
    modeButtonText: {
      color: '#bacac7',
      fontSize: 12,
      fontWeight: 'bold',
    },
    modeButtonTextActive: {
      color: '#003734',
    },
    apiFields: {
      marginTop: 8,
    },
    fieldLabel: {
      color: '#bacac7',
      fontSize: 12,
      marginBottom: 4,
      marginTop: 6,
    },
  }),
};
