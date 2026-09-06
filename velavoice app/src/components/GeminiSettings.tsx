import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
  Linking,
} from 'react-native';
import {
  testGeminiApiKey,
  saveGeminiApiKey,
  getGeminiApiKey,
  saveGeminiModel,
  getGeminiModel,
  SUPPORTED_GEMINI_MODELS,
  GEMINI_MODEL,
  GOOGLE_AI_STUDIO_URL,
} from '../services/GeminiService';

export interface GeminiSettingsProps {
  /** Current active transcription mode (e.g. 'gemini', 'local', 'groq', 'openai') */
  currentMode?: string;
  /** Callback when user selects Gemini 2.0 Flash provider */
  onSelectProvider?: (mode: string) => void;
  /** Callback when API key is saved or updated */
  onApiKeyChange?: (key: string) => void;
}

export const GeminiSettings: React.FC<GeminiSettingsProps> = ({
  currentMode,
  onSelectProvider,
  onApiKeyChange,
}) => {
  const [apiKey, setApiKey] = useState<string>('');
  const [selectedModel, setSelectedModel] = useState<string>(GEMINI_MODEL);
  const [showKey, setShowKey] = useState<boolean>(false);
  const [isTesting, setIsTesting] = useState<boolean>(false);
  const [testStatus, setTestStatus] = useState<{
    type: 'idle' | 'success' | 'error';
    message: string;
  }>({ type: 'idle', message: '' });
  const [isLoadingKey, setIsLoadingKey] = useState<boolean>(true);

  // Load API key & model from SecureStore on mount
  useEffect(() => {
    let isMounted = true;
    (async () => {
      try {
        const [storedKey, storedModel] = await Promise.all([
          getGeminiApiKey(),
          getGeminiModel(),
        ]);
        if (isMounted) {
          if (storedKey) {
            setApiKey(storedKey);
            onApiKeyChange?.(storedKey);
          }
          if (storedModel) {
            setSelectedModel(storedModel);
          }
        }
      } catch (e) {
        console.error('Failed to load Gemini settings from storage:', e);
      } finally {
        if (isMounted) {
          setIsLoadingKey(false);
        }
      }
    })();

    return () => {
      isMounted = false;
    };
  }, [onApiKeyChange]);

  const handleKeyChange = useCallback(
    async (val: string) => {
      setApiKey(val);
      setTestStatus({ type: 'idle', message: '' });
      try {
        await saveGeminiApiKey(val);
        onApiKeyChange?.(val);
        if (val.trim()) {
          onSelectProvider?.('gemini');
        }
      } catch (e) {
        console.error('Failed to save Gemini API key to SecureStore:', e);
      }
    },
    [onApiKeyChange, onSelectProvider]
  );

  const handleSelectModel = useCallback(async (modelId: string) => {
    setSelectedModel(modelId);
    setTestStatus({ type: 'idle', message: '' });
    try {
      await saveGeminiModel(modelId);
    } catch (e) {
      console.error('Failed to save Gemini model to storage:', e);
    }
  }, []);

  const handleTestConnection = useCallback(async () => {
    if (!apiKey.trim()) {
      setTestStatus({
        type: 'error',
        message: 'Please enter an API key first.',
      });
      return;
    }

    setIsTesting(true);
    setTestStatus({ type: 'idle', message: '' });

    try {
      // Ensure current key & model are saved to storage before testing
      await Promise.all([
        saveGeminiApiKey(apiKey.trim()),
        saveGeminiModel(selectedModel),
      ]);

      const result = await testGeminiApiKey(apiKey.trim(), selectedModel);
      if (result.success) {
        onSelectProvider?.('gemini');
        setTestStatus({
          type: 'success',
          message: `Connection successful! Google Gemini (${selectedModel}) is active and ready for voice transcription & rewrites.`,
        });
      } else {
        setTestStatus({
          type: 'error',
          message: result.error || 'Connection test failed. Please verify your API key.',
        });
      }
    } catch (e: any) {
      setTestStatus({
        type: 'error',
        message: e?.message || 'Unexpected error while testing connection.',
      });
    } finally {
      setIsTesting(false);
    }
  }, [apiKey, selectedModel]);

  const handleOpenGoogleAIStudio = useCallback(async () => {
    try {
      const supported = await Linking.canOpenURL(GOOGLE_AI_STUDIO_URL);
      if (supported) {
        await Linking.openURL(GOOGLE_AI_STUDIO_URL);
      } else {
        await Linking.openURL(GOOGLE_AI_STUDIO_URL);
      }
    } catch (e) {
      console.error('Failed to open Google AI Studio URL:', e);
    }
  }, []);

  const isGeminiSelected = currentMode === 'gemini';

  return (
    <View style={styles.container}>
        {/* Provider Picker Option */}
        <View style={styles.providerSection}>
          <Text style={styles.sectionLabel}>AI Provider</Text>
          <TouchableOpacity
            style={[
              styles.providerButton,
              isGeminiSelected && styles.providerButtonActive,
            ]}
            onPress={() => onSelectProvider?.('gemini')}
            activeOpacity={0.7}
          >
            <View style={styles.providerInfo}>
              <Text style={styles.providerIcon}>✨</Text>
              <View>
                <Text
                  style={[
                    styles.providerName,
                    isGeminiSelected && styles.providerNameActive,
                  ]}
                >
                  Google Gemini 3.5 Transcribe (Live & Batch)
                </Text>
                <Text style={styles.providerSubtext}>
                  Sub-second live streaming & batch audio transcription with timestamps
                </Text>
              </View>
            </View>
            <View
              style={[
                styles.radioCircle,
                isGeminiSelected && styles.radioCircleActive,
              ]}
            >
              {isGeminiSelected && <View style={styles.radioDot} />}
            </View>
          </TouchableOpacity>
        </View>

        {/* Model Selection */}
        <View style={styles.modelSection}>
          <Text style={styles.fieldLabel}>Gemini Model</Text>
          <View style={styles.modelChipsRow}>
            {SUPPORTED_GEMINI_MODELS.map((m) => {
              const isSelected = selectedModel === m.id;
              return (
                <TouchableOpacity
                  key={m.id}
                  style={[
                    styles.modelChip,
                    isSelected && styles.modelChipActive,
                  ]}
                  onPress={() => handleSelectModel(m.id)}
                  activeOpacity={0.7}
                >
                  <Text
                    style={[
                      styles.modelChipText,
                      isSelected && styles.modelChipTextActive,
                    ]}
                  >
                    {m.name}
                  </Text>
                </TouchableOpacity>
              );
            })}
          </View>
        </View>

        {/* API Key Management */}
        <View style={styles.apiKeySection}>
        <Text style={styles.fieldLabel}>Gemini API Key</Text>

        <View style={styles.inputRow}>
          <TextInput
            style={styles.inputField}
            placeholder="AIzaSy..."
            placeholderTextColor="#859491"
            value={apiKey}
            onChangeText={handleKeyChange}
            secureTextEntry={!showKey}
            autoCapitalize="none"
            autoCorrect={false}
            editable={!isLoadingKey}
          />
          <TouchableOpacity
            style={styles.toggleButton}
            onPress={() => setShowKey((prev) => !prev)}
            accessibilityLabel={showKey ? 'Hide API key' : 'Show API key'}
          >
            <Text style={styles.toggleButtonText}>
              {showKey ? 'Hide' : 'Show'}
            </Text>
          </TouchableOpacity>
        </View>

        {/* Test Connection Button */}
        <TouchableOpacity
          style={[styles.testButton, isTesting && styles.testButtonDisabled]}
          onPress={handleTestConnection}
          disabled={isTesting || isLoadingKey}
          activeOpacity={0.8}
        >
          {isTesting ? (
            <View style={styles.loadingRow}>
              <ActivityIndicator size="small" color="#111a19" />
              <Text style={styles.testButtonTextLoading}>Validating Key...</Text>
            </View>
          ) : (
            <Text style={styles.testButtonText}>Test Connection</Text>
          )}
        </TouchableOpacity>

        {/* Test Status Banner */}
        {testStatus.type !== 'idle' && (
          <View
            style={[
              styles.statusBanner,
              testStatus.type === 'success'
                ? styles.statusSuccess
                : styles.statusError,
            ]}
          >
            <Text
              style={[
                styles.statusText,
                testStatus.type === 'success'
                  ? styles.statusTextSuccess
                  : styles.statusTextError,
              ]}
            >
              {testStatus.message}
            </Text>
          </View>
        )}

        {/* Helper Text & Google AI Studio link */}
        <View style={styles.helperCard}>
          <Text style={styles.helperTitle}>Need a free API key?</Text>
          <Text style={styles.helperText}>
            Get a free Gemini API key from Google AI Studio. Google provides generous free tier limits (up to 15 requests/min) with no credit card required.
          </Text>
          <TouchableOpacity
            style={styles.studioLink}
            onPress={handleOpenGoogleAIStudio}
          >
            <Text style={styles.studioLinkText}>
              ?? Get API Key at Google AI Studio (aistudio.google.com) ?
            </Text>
          </TouchableOpacity>
        </View>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    marginVertical: 8,
  },
  providerSection: {
    marginBottom: 16,
  },
  sectionLabel: {
    color: '#859491',
    fontSize: 12,
    fontWeight: 'bold',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    marginBottom: 8,
  },
  providerButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: '#172221',
    borderWidth: 1,
    borderColor: '#253533',
    borderRadius: 12,
    padding: 14,
  },
  providerButtonActive: {
    backgroundColor: '#162e2c',
    borderColor: '#62f9ee',
  },
  providerInfo: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
    marginRight: 10,
  },
  providerIcon: {
    fontSize: 22,
    marginRight: 12,
  },
  providerName: {
    color: '#d1e0de',
    fontSize: 14,
    fontWeight: 'bold',
  },
  providerNameActive: {
    color: '#62f9ee',
  },
  providerSubtext: {
    color: '#859491',
    fontSize: 11,
    marginTop: 2,
  },
  radioCircle: {
    width: 20,
    height: 20,
    borderRadius: 10,
    borderWidth: 2,
    borderColor: '#3c4948',
    alignItems: 'center',
    justifyContent: 'center',
  },
  radioCircleActive: {
    borderColor: '#62f9ee',
  },
  radioDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: '#62f9ee',
  },
  modelSection: {
    marginBottom: 16,
    backgroundColor: '#172221',
    borderWidth: 1,
    borderColor: '#253533',
    borderRadius: 12,
    padding: 14,
  },
  modelChipsRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginTop: 6,
  },
  modelChip: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#111a19',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#253533',
  },
  modelChipActive: {
    backgroundColor: '#162e2c',
    borderColor: '#62f9ee',
  },
  modelChipText: {
    color: '#859491',
    fontSize: 12,
    fontWeight: '600',
  },
  modelChipTextActive: {
    color: '#62f9ee',
    fontWeight: 'bold',
  },
  apiKeySection: {
    backgroundColor: '#172221',
    borderWidth: 1,
    borderColor: '#253533',
    borderRadius: 12,
    padding: 14,
  },
  fieldLabel: {
    color: '#d1e0de',
    fontSize: 13,
    fontWeight: 'bold',
    marginBottom: 6,
  },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#111a19',
    borderWidth: 1,
    borderColor: '#253533',
    borderRadius: 8,
    overflow: 'hidden',
  },
  inputField: {
    flex: 1,
    paddingHorizontal: 12,
    paddingVertical: 10,
    color: '#ffffff',
    fontSize: 14,
  },
  toggleButton: {
    paddingHorizontal: 14,
    paddingVertical: 10,
    backgroundColor: '#1f2b2a',
    borderLeftWidth: 1,
    borderLeftColor: '#253533',
  },
  toggleButtonText: {
    color: '#62f9ee',
    fontSize: 12,
    fontWeight: 'bold',
  },
  testButton: {
    backgroundColor: '#62f9ee',
    borderRadius: 8,
    paddingVertical: 12,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 12,
  },
  testButtonDisabled: {
    opacity: 0.6,
  },
  testButtonText: {
    color: '#111a19',
    fontSize: 14,
    fontWeight: 'bold',
  },
  loadingRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  testButtonTextLoading: {
    color: '#111a19',
    fontSize: 14,
    fontWeight: 'bold',
    marginLeft: 8,
  },
  statusBanner: {
    borderRadius: 8,
    padding: 12,
    marginTop: 12,
  },
  statusSuccess: {
    backgroundColor: 'rgba(74, 222, 128, 0.12)',
    borderWidth: 1,
    borderColor: '#4ade80',
  },
  statusError: {
    backgroundColor: 'rgba(255, 107, 107, 0.12)',
    borderWidth: 1,
    borderColor: '#ff6b6b',
  },
  statusText: {
    fontSize: 12,
    lineHeight: 16,
  },
  statusTextSuccess: {
    color: '#4ade80',
  },
  statusTextError: {
    color: '#ff6b6b',
  },
  helperCard: {
    marginTop: 14,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: '#253533',
  },
  helperTitle: {
    color: '#d1e0de',
    fontSize: 12,
    fontWeight: 'bold',
    marginBottom: 4,
  },
  helperText: {
    color: '#859491',
    fontSize: 11,
    lineHeight: 16,
    marginBottom: 8,
  },
  studioLink: {
    paddingVertical: 4,
  },
  studioLinkText: {
    color: '#62f9ee',
    fontSize: 12,
    fontWeight: '600',
  },
});

export default GeminiSettings;
