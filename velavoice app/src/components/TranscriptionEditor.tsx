import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ScrollView,
} from 'react-native';
import { calculateEditDistance, getEdits } from '../utils/editCalculator';
import { isQuarantinedEntry, quarantineBadgeText } from '../utils/quarantineBadge';
import { ScribeAI } from '../services/ScribeAI';

export interface TranscriptionEditorProps {
  audioId: string;
  originalTranscription: string;
  onSave: (
    audioId: string,
    original: string,
    corrected: string,
    edits: any[],
    editDistance: number,
    scribeStyle?: string,
    quarantined?: boolean
  ) => void;
  onCancel: () => void;
  // Quarantined sessions stay editable on-device but show an explicit
  // local-only badge and never egress (ticket #136).
  quarantined?: boolean;
}

const SCRIBE_STYLES = ['Professional', 'Casual', 'Bullet Points', 'Email Draft', 'Proofread', 'Custom'];
const AI_MODELS = ['gemini', 'groq'] as const;
type AIModel = typeof AI_MODELS[number];

export const TranscriptionEditor: React.FC<TranscriptionEditorProps> = ({
  audioId,
  originalTranscription,
  onSave,
  onCancel,
  quarantined,
}) => {
  const [correctedText, setCorrectedText] = useState(originalTranscription);
  const [selectedStyle, setSelectedStyle] = useState('Professional');
  const [isPlayingAudio, setIsPlayingAudio] = useState(false);
  const [isReCleaning, setIsReCleaning] = useState(false);
  const [selectedModel, setSelectedModel] = useState<AIModel>('gemini');
  const [isAIReWriting, setIsAIReWriting] = useState(false);
  const [aiError, setAiError] = useState<string | null>(null);
  const [customPrompt, setCustomPrompt] = useState('');
  const [showPromptInput, setShowPromptInput] = useState(false);
  // Hydrated once on mount from isConfiguredAsync (SecureStore-aware);
  // default false so rewrite stays disabled until the check resolves.
  const [configuredModels, setConfiguredModels] = useState<Record<AIModel, boolean>>({
    gemini: false,
    groq: false,
  });

  useEffect(() => {
    let alive = true;
    (async () => {
      const [gemini, groq] = await Promise.all([
        ScribeAI.isConfiguredAsync('gemini'),
        ScribeAI.isConfiguredAsync('groq'),
      ]);
      if (alive) setConfiguredModels({ gemini, groq });
    })();
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    setCorrectedText(originalTranscription);
  }, [originalTranscription]);

  const handleTogglePlayback = () => {
    setIsPlayingAudio(!isPlayingAudio);
  };

  const handleReCleanTranscript = () => {
    setIsReCleaning(true);
    setTimeout(() => {
      let rewritten = originalTranscription;
      if (selectedStyle === 'Casual') {
        rewritten = `Hey! ${originalTranscription.toLowerCase()}`;
      } else if (selectedStyle === 'Bullet Points') {
        rewritten = `• ${originalTranscription.replace(/\. /g, '\n• ')}`;
      } else if (selectedStyle === 'Email Draft') {
        rewritten = `Hi Team,\n\n${originalTranscription}\n\nBest regards,`;
      } else if (selectedStyle === 'Professional') {
        rewritten = `${originalTranscription.trim()}.`;
      }
      setCorrectedText(rewritten);
      setIsReCleaning(false);
    }, 300);
  };

  const handleAIReWrite = async () => {
    if (!correctedText.trim()) return;

    setIsAIReWriting(true);
    setAiError(null);

    try {
      const result = await ScribeAI.rewrite({
        model: selectedModel,
        text: correctedText,
        style: showPromptInput && customPrompt.trim() ? 'Custom' : selectedStyle,
        customPrompt: showPromptInput && customPrompt.trim() ? customPrompt : undefined,
      });

      if (result.success && result.text) {
        setCorrectedText(result.text);
        if (showPromptInput) {
          setCustomPrompt('');
        }
      } else {
        setAiError(result.error || 'Rewrite failed');
      }
    } catch (err: any) {
      setAiError(err.message || 'Rewrite failed');
    } finally {
      setIsAIReWriting(false);
    }
  };

  const handleSave = () => {
    const edits = getEdits(originalTranscription, correctedText);
    const editDistance = calculateEditDistance(originalTranscription, correctedText);
    onSave(audioId, originalTranscription, correctedText, edits, editDistance, selectedStyle, quarantined === true);
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.scrollContent}>
      {isQuarantinedEntry({ quarantined }) && (
        <View style={styles.quarantineBanner}>
          <Text style={styles.quarantineBannerText}>
            🔒 {quarantineBadgeText()} — edits stay on this device
          </Text>
        </View>
      )}
      <Text style={styles.sectionTitle}>Decrypted PCM Audio Snippet ({audioId})</Text>
      <View style={styles.waveformContainer}>
        <TouchableOpacity style={styles.playButton} onPress={handleTogglePlayback}>
          <Text style={styles.playButtonText}>{isPlayingAudio ? 'Pause' : 'Play Audio'}</Text>
        </TouchableOpacity>
        <View style={styles.waveformBarContainer}>
          <Text style={styles.waveformText}>
            {isPlayingAudio ? '|||||||||||||||||||||||||| (Playing)' : '||| |||| |||||| |||| ||| (Ready)'}
          </Text>
        </View>
      </View>

      <Text style={styles.sectionTitle}>Original Transcription</Text>
      <View style={styles.readOnlyContainer}>
        <Text style={styles.readOnlyText}>{originalTranscription}</Text>
      </View>

      <Text style={styles.sectionTitle}>Scribe Re-Cleaning Style</Text>
      <View style={styles.stylePickerRow}>
        {SCRIBE_STYLES.map((style) => (
          <TouchableOpacity
            key={style}
            style={[styles.styleChip, selectedStyle === style && styles.activeStyleChip]}
            onPress={() => setSelectedStyle(style)}
          >
            <Text style={[styles.styleChipText, selectedStyle === style && styles.activeStyleChipText]}>
              {style}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      <TouchableOpacity
        style={[styles.recleanButton, isReCleaning && styles.disabledButton]}
        onPress={handleReCleanTranscript}
        disabled={isReCleaning}
      >
        <Text style={styles.recleanButtonText}>
          {isReCleaning ? 'Re-Cleaning Scribe...' : `Re-Clean with ${selectedStyle} Style`}
        </Text>
      </TouchableOpacity>

      <Text style={styles.sectionTitle}>AI Rewrite Tools</Text>
      <TouchableOpacity style={styles.promptToggle} onPress={() => setShowPromptInput(!showPromptInput)}>
        <Text style={styles.promptToggleText}>
          {showPromptInput ? 'Hide Custom Prompt' : 'Use Custom Prompt'}
        </Text>
      </TouchableOpacity>

      {showPromptInput && (
        <TextInput
          style={styles.customPromptInput}
          value={customPrompt}
          onChangeText={setCustomPrompt}
          placeholder="Type your own rewrite instructions..."
          placeholderTextColor="#5a6f6d"
          multiline
          numberOfLines={2}
        />
      )}

      <View style={styles.aiToolbarRow}>
        {AI_MODELS.map((model) => (
          <TouchableOpacity
            key={model}
            style={[
              styles.aiModelChip,
              selectedModel === model && styles.activeAiModelChip,
              !configuredModels[model] && styles.unconfiguredChip,
            ]}
            onPress={() => configuredModels[model] && setSelectedModel(model)}
          >
            <Text style={[
              styles.aiModelChipText,
              selectedModel === model && styles.activeAiModelChipText,
              !configuredModels[model] && styles.unconfiguredChipText,
            ]}>
              {model === 'gemini' ? 'Gemini' : 'Groq'}
              {!configuredModels[model] ? ' (off)' : ''}
            </Text>
          </TouchableOpacity>
        ))}
        <TouchableOpacity
          style={[
            styles.aiRewriteButton,
            (isAIReWriting || !configuredModels[selectedModel] || !correctedText.trim()) && styles.disabledButton,
          ]}
          onPress={handleAIReWrite}
          disabled={isAIReWriting || !configuredModels[selectedModel] || !correctedText.trim()}
        >
          <Text style={styles.aiRewriteButtonText}>
            {isAIReWriting ? 'Rewriting...' :
             showPromptInput && customPrompt.trim() ? 'Apply Prompt' :
             `Rewrite with ${selectedModel === 'gemini' ? 'Gemini' : 'Groq'}`}
          </Text>
        </TouchableOpacity>
      </View>

      {aiError && <Text style={styles.errorText}>{aiError}</Text>}

      <Text style={styles.sectionTitle}>Final Transcription</Text>
      <TextInput
        style={styles.textInput}
        value={correctedText}
        onChangeText={setCorrectedText}
        multiline
        numberOfLines={6}
        textAlignVertical="top"
        placeholder="Edit transcription here..."
        placeholderTextColor="#5a6f6d"
      />

      <View style={styles.buttonRow}>
        <TouchableOpacity style={[styles.button, styles.cancelButton]} onPress={onCancel}>
          <Text style={styles.buttonText}>Cancel</Text>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.button, styles.saveButton]} onPress={handleSave}>
          <Text style={[styles.buttonText, styles.saveButtonText]}>Save Correction</Text>
        </TouchableOpacity>
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#111716' },
  scrollContent: { padding: 16 },
  quarantineBanner: { backgroundColor: '#5c1a1a', borderColor: '#ff6b6b', borderWidth: 1, borderRadius: 6, padding: 10, marginBottom: 4 },
  quarantineBannerText: { color: '#ffffff', fontSize: 12, fontWeight: 'bold' },
  sectionTitle: { color: '#859491', fontSize: 12, fontWeight: 'bold', textTransform: 'uppercase', marginBottom: 6, letterSpacing: 0.5, marginTop: 12 },
  waveformContainer: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#0a0d0d', padding: 10, borderRadius: 6, marginBottom: 12 },
  playButton: { backgroundColor: '#00d6aa', paddingHorizontal: 12, paddingVertical: 6, borderRadius: 4 },
  playButtonText: { color: '#091515', fontWeight: 'bold', fontSize: 12 },
  waveformBarContainer: { marginLeft: 12, flex: 1 },
  waveformText: { color: '#88f3dd', fontFamily: 'monospace', fontSize: 12 },
  readOnlyContainer: { backgroundColor: '#0a0d0d', borderColor: '#212928', borderWidth: 1, borderRadius: 6, padding: 10, marginBottom: 12 },
  readOnlyText: { color: '#a7b5b2', fontSize: 14 },
  stylePickerRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginBottom: 10 },
  styleChip: { backgroundColor: '#1c2624', paddingHorizontal: 10, paddingVertical: 6, borderRadius: 14 },
  activeStyleChip: { backgroundColor: '#00d6aa' },
  styleChipText: { color: '#a7b5b2', fontSize: 12 },
  activeStyleChipText: { color: '#091515', fontWeight: 'bold' },
  recleanButton: { backgroundColor: '#1b3834', borderColor: '#00d6aa', borderWidth: 1, padding: 10, borderRadius: 6, alignItems: 'center', marginBottom: 14 },
  recleanButtonText: { color: '#00d6aa', fontWeight: 'bold', fontSize: 13 },
  promptToggle: { alignSelf: 'flex-start', paddingHorizontal: 12, paddingVertical: 6, borderRadius: 12, backgroundColor: '#1c2624', borderWidth: 1, borderColor: '#293533', marginBottom: 8 },
  promptToggleText: { color: '#859491', fontSize: 11, fontWeight: '600' },
  customPromptInput: { backgroundColor: '#050707', borderColor: '#293533', borderWidth: 1, borderRadius: 6, color: '#ffffff', fontSize: 13, padding: 10, marginBottom: 10, minHeight: 60 },
  aiToolbarRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, alignItems: 'center', marginBottom: 10 },
  aiModelChip: { backgroundColor: '#1c2624', paddingHorizontal: 12, paddingVertical: 8, borderRadius: 16, borderWidth: 1, borderColor: '#293533' },
  activeAiModelChip: { backgroundColor: '#1b3834', borderColor: '#00d6aa' },
  unconfiguredChip: { opacity: 0.5 },
  aiModelChipText: { color: '#a7b5b2', fontSize: 12, fontWeight: '600' },
  activeAiModelChipText: { color: '#00d6aa' },
  unconfiguredChipText: { color: '#5a6f6d' },
  aiRewriteButton: { backgroundColor: '#00d6aa', paddingHorizontal: 14, paddingVertical: 8, borderRadius: 16 },
  aiRewriteButtonText: { color: '#091515', fontWeight: 'bold', fontSize: 12 },
  errorText: { color: '#ff6b6b', fontSize: 12, marginBottom: 10, fontStyle: 'italic' },
  textInput: { backgroundColor: '#050707', borderColor: '#293533', borderWidth: 1, borderRadius: 6, color: '#ffffff', fontSize: 14, padding: 10, minHeight: 120, marginBottom: 14 },
  buttonRow: { flexDirection: 'row', justifyContent: 'flex-end', gap: 8, marginBottom: 20 },
  button: { paddingVertical: 10, paddingHorizontal: 16, borderRadius: 6 },
  cancelButton: { backgroundColor: '#1c2624' },
  saveButton: { backgroundColor: '#00d6aa' },
  buttonText: { color: '#a7b5b2', fontSize: 14, fontWeight: 'bold' },
  saveButtonText: { color: '#091515' },
  disabledButton: { opacity: 0.5 },
});
