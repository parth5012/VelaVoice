import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  StyleSheet,
  Text,
  View,
  Image,
  FlatList,
  ActivityIndicator,
  TouchableOpacity,
  Switch,
  TextInput,
  AppState,
  AppStateStatus,
  NativeModules,
  Platform,
  PermissionsAndroid,
  ScrollView,
  SafeAreaView,
  Dimensions,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { ModelManager, ModelInfo, DictionaryEntry } from './src/services/ModelManager';
import OverlayLogo from './src/components/OverlayLogo';

interface Recording {
  id: string;
  title: string;
  date: string;
  size: string;
  raw: string;
  cleaned: string;
  wave: number[];
}

// Memoized recording card for FlatList performance
const RecordingCard = React.memo(({ item, isSelected, onPress }: {
  item: Recording;
  isSelected: boolean;
  onPress: () => void;
}) => (
  <TouchableOpacity
    style={[styles.recordingCard, isSelected && styles.recordingCardSelected]}
    onPress={onPress}
  >
    <View style={styles.recCardHeader}>
      <View>
        <Text style={styles.recTitle}>{item.title}</Text>
        <Text style={styles.recDate}>{item.date} • {item.size}</Text>
      </View>
      <Text style={styles.recChevron}>➔</Text>
    </View>
    <View style={styles.recWaveContainer}>
      {item.wave.map((h, i) => (
        <View
          key={i}
          style={[
            styles.recWaveBar,
            { height: h },
            isSelected ? { backgroundColor: '#62f9ee' } : { backgroundColor: '#859491' }
          ]}
        />
      ))}
    </View>
  </TouchableOpacity>
));

export default function App() {
  const [activeTab, setActiveTab] = useState<'hub' | 'studio' | 'engine'>('hub');
  
  // Model Management States
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [downloadProgress, setDownloadProgress] = useState<{ [key: string]: number }>({});

  // System & Permission States
  const [isAccessibilityEnabled, setIsAccessibilityEnabled] = useState(false);
  const [useLlmCleaner, setUseLlmCleaner] = useState(false);
  const [hasMicPermission, setHasMicPermission] = useState(false);

  // Transcription API States
  const [transcriptionMode, setTranscriptionMode] = useState<string>('local');
  const [groqApiKey, setGroqApiKey] = useState<string>('');
  const [groqModel, setGroqModel] = useState<string>('whisper-large-v3');
  const [openaiApiKey, setOpenaiApiKey] = useState<string>('');
  const [openaiModel, setOpenaiModel] = useState<string>('whisper-1');
  const [openaiEndpoint, setOpenaiEndpoint] = useState<string>('https://api.openai.com/v1');

  const updatePreference = async (key: string, value: string, setter: (val: string) => void) => {
    setter(value);
    if (NativeModules.ModelVerifier) {
      try {
        await NativeModules.ModelVerifier.setStringPreference(key, value);
      } catch (e) {
        console.error(`Failed to save preference ${key}`, e);
      }
    }
  };

  // Personal Dictionary States
  const [dictionary, setDictionary] = useState<DictionaryEntry[]>([]);
  const [originalWord, setOriginalWord] = useState('');
  const [replacement, setReplacement] = useState('');
  const [language, setLanguage] = useState('');
  const [priority, setPriority] = useState('1');

  // Recordings Library (Voice Hub / Studio)
  const [recordings, setRecordings] = useState<Recording[]>([
    {
      id: '1',
      title: 'Marketing Sync Ideas',
      date: '12:45 • 4m 32s',
      size: '1.2 MB',
      raw: 'So, um, today we need to talk about the marketing plan. We should, like, focus on developer outreach, you know? And maybe run some ads.',
      cleaned: 'Today we need to talk about the marketing plan. We should focus on developer outreach and run advertising campaigns.',
      wave: [10, 18, 12, 28, 20, 15, 8, 16, 12, 24, 10, 18, 14, 8, 22, 12]
    },
    {
      id: '2',
      title: 'Morning Dev Jam',
      date: '09:15 • 12m 04s',
      size: '3.4 MB',
      raw: 'Okay, so the SQLite db needs to be initialized. Uh, we have some tables. Models and personal dictionary. Let\'s, um, make sure they have indexes.',
      cleaned: 'The SQLite database needs to be initialized. We have models and personal dictionary tables. Let\'s make sure they have indexes.',
      wave: [8, 14, 10, 22, 16, 12, 6, 14, 10, 20, 8, 16, 12, 6, 18, 10]
    }
  ]);
  const [selectedRecordingId, setSelectedRecordingId] = useState<string>('1');

  // Recording Simulation States
  const [isRecording, setIsRecording] = useState(false);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const [recordingAmplitudes, setRecordingAmplitudes] = useState<number[]>([]);

  // Custom text test state in Studio
  const [testText, setTestText] = useState('');
  const [testCleanedText, setTestCleanedText] = useState('');

  // Selected Recording Transcript View Segment Tab
  const [studioSegment, setStudioSegment] = useState<'cleaned' | 'raw'>('cleaned');
  const [isEditingTranscript, setIsEditingTranscript] = useState(false);
  const [editingTextValue, setEditingTextValue] = useState('');

  useEffect(() => {
    loadModels();
    loadDictionary();
    checkAccessibilityStatus();
    checkMicPermission();

    const subscription = AppState.addEventListener('change', (nextAppState: AppStateStatus) => {
      if (nextAppState === 'active') {
        checkAccessibilityStatus();
        checkMicPermission();
      }
    });

    return () => {
      subscription.remove();
    };
  }, []);

  // Update editing text when recording selection or segment changes
  useEffect(() => {
    const activeRec = recordings.find(r => r.id === selectedRecordingId);
    if (activeRec) {
      setEditingTextValue(studioSegment === 'cleaned' ? activeRec.cleaned : activeRec.raw);
    } else {
      setEditingTextValue('');
    }
  }, [selectedRecordingId, studioSegment, recordings]);

  // Recording Simulation logic
  useEffect(() => {
    let interval: NodeJS.Timeout;
    let timer: NodeJS.Timeout;
    if (isRecording) {
      setRecordingSeconds(0);
      setRecordingAmplitudes([]);
      timer = setInterval(() => {
        setRecordingSeconds(prev => prev + 1);
      }, 1000);
      
      interval = setInterval(() => {
        const amp = Math.floor(Math.random() * 32) + 4;
        setRecordingAmplitudes(prev => {
          const next = [...prev, amp];
          if (next.length > 20) {
            next.shift();
          }
          return next;
        });
      }, 150);
    }
    return () => {
      clearInterval(interval);
      clearInterval(timer);
    };
  }, [isRecording]);

  const loadModels = useCallback(async () => {
    try {
      const list = await ModelManager.getModels();
      setModels(list);
    } catch (e) {
      console.error('Failed to load models', e);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadDictionary = useCallback(async () => {
    try {
      const list = await ModelManager.getDictionaryEntries();
      setDictionary(list);
    } catch (e) {
      console.error('Failed to load dictionary', e);
    }
  }, []);

  const handleAddEntry = useCallback(async () => {
    if (!originalWord.trim() || !replacement.trim()) {
      alert('Please fill out both the original word and its replacement.');
      return;
    }

    try {
      await ModelManager.addDictionaryEntry(
        originalWord.trim(),
        replacement.trim(),
        language.trim() || null,
        parseInt(priority, 10) || 1
      );
      setOriginalWord('');
      setReplacement('');
      setLanguage('');
      setPriority('1');
      await loadDictionary();
    } catch (e) {
      console.error('Failed to add dictionary entry', e);
      alert('Failed to add entry. Word might already exist.');
    }
  }, [originalWord, replacement, language, priority, loadDictionary]);

  const handleDeleteEntry = useCallback(async (id?: number) => {
    if (id === undefined) return;
    try {
      await ModelManager.deleteDictionaryEntry(id);
      await loadDictionary();
    } catch (e) {
      console.error('Failed to delete dictionary entry', e);
      alert('Failed to delete entry.');
    }
  }, [loadDictionary]);

  const checkAccessibilityStatus = useCallback(async () => {
    if (NativeModules.ModelVerifier) {
      try {
        // Batch load accessibility + all preferences in parallel
        const [enabled, useLlm, prefsJson] = await Promise.all([
          NativeModules.ModelVerifier.isAccessibilityServiceEnabled(),
          NativeModules.ModelVerifier.getUseLlmCleaner(),
          NativeModules.ModelVerifier.getAllPreferences()
            .then((json: string) => JSON.parse(json))
            .catch(() => null)
        ]);

        setIsAccessibilityEnabled(enabled);
        setUseLlmCleaner(useLlm);

        if (prefsJson) {
          setTranscriptionMode(prefsJson.transcriptionMode || 'local');
          setGroqApiKey(prefsJson.groqApiKey || '');
          setGroqModel(prefsJson.groqModel || 'whisper-large-v3');
          setOpenaiApiKey(prefsJson.openaiApiKey || '');
          setOpenaiModel(prefsJson.openaiModel || 'whisper-1');
          setOpenaiEndpoint(prefsJson.openaiEndpoint || 'https://api.openai.com/v1');
        }
      } catch (e) {
        console.error('Failed to load preferences', e);
      }
    }
  };

  const checkMicPermission = async () => {
    if (Platform.OS === 'android') {
      try {
        const hasPermission = await PermissionsAndroid.check(
          PermissionsAndroid.PERMISSIONS.RECORD_AUDIO
        );
        setHasMicPermission(hasPermission);
      } catch (e) {
        console.error('Failed to check mic permission', e);
      }
    } else {
      setHasMicPermission(true);
    }
  };

  const requestMicPermission = async () => {
    if (Platform.OS === 'android') {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
          {
            title: 'Microphone Permission',
            message: 'Vela Voice needs access to your microphone to transcribe audio offline.',
            buttonNeutral: 'Ask Later',
            buttonNegative: 'Cancel',
            buttonPositive: 'OK',
          }
        );
        const hasPermission = granted === PermissionsAndroid.RESULTS.GRANTED;
        setHasMicPermission(hasPermission);
        return hasPermission;
      } catch (e) {
        console.error('Failed to request mic permission', e);
        return false;
      }
    }
    return true;
  };

  const handleOpenAccessibilitySettings = () => {
    if (NativeModules.ModelVerifier) {
      NativeModules.ModelVerifier.openAccessibilitySettings();
    }
  };

  const handleToggleLlm = async (value: boolean) => {
    if (NativeModules.ModelVerifier) {
      try {
        await NativeModules.ModelVerifier.setUseLlmCleaner(value);
        setUseLlmCleaner(value);
      } catch (e) {
        console.error('Failed to toggle LLM Cleaner', e);
      }
    } else {
      setUseLlmCleaner(value);
    }
  };

  const handleDownload = useCallback(async (id: string) => {
    setDownloadProgress((prev) => ({ ...prev, [id]: 0 }));
    setModels((prevModels) =>
      prevModels.map((m) => (m.id === id ? { ...m, status: 'downloading' } : m))
    );

    try {
      await ModelManager.downloadModel(id, (progress: number) => {
        setDownloadProgress((prev) => ({ ...prev, [id]: progress }));
      });
    } catch (e) {
      console.error('Download failed', e);
    } finally {
      loadModels();
    }
  }, [loadModels]);

  const handleDelete = useCallback(async (id: string) => {
    try {
      await ModelManager.deleteModel(id);
      loadModels();
    } catch (e) {
      console.error('Failed to delete model', e);
    }
  }, [loadModels]);

  // Run Personal Dictionary Clean simulation
  const runCustomClean = () => {
    if (!testText.trim()) return;
    let cleaned = testText;
    const sortedDict = [...dictionary].sort((a, b) => (b.priority || 0) - (a.priority || 0));
    for (const entry of sortedDict) {
      if (entry.original_word) {
        const regex = new RegExp(`\\b${entry.original_word}\\b`, 'gi');
        cleaned = cleaned.replace(regex, entry.replacement);
      }
    }
    setTestCleanedText(cleaned);
  };

  // Start simulated recording
  const startRecordingSim = async () => {
    const hasPermission = await requestMicPermission();
    if (!hasPermission) {
      alert('Microphone permission required to record.');
      return;
    }
    setIsRecording(true);
  };

  // Stop simulated recording and process
  const stopRecordingSim = (runCleaner: boolean) => {
    setIsRecording(false);
    const mins = Math.floor(recordingSeconds / 60);
    const secs = recordingSeconds % 60;
    const durationStr = `${mins}:${secs < 10 ? '0' : ''}${secs}`;
    const newId = (recordings.length + 1).toString();
    
    // Simulate raw vs cleaned transcription
    const rawText = "So, like, this is, um, a new recording from the, you know, Vela Voice App Hub tab. We are recording audio locally.";
    const cleanedText = runCleaner 
      ? "This is a new recording from the Vela Voice App Hub tab. We are recording audio locally."
      : rawText;

    const newRec: Recording = {
      id: newId,
      title: `Voice Memo ${newId}`,
      date: `${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} • ${durationStr}`,
      size: '0.9 MB',
      raw: rawText,
      cleaned: cleanedText,
      wave: recordingAmplitudes.length > 0 ? recordingAmplitudes : [8, 15, 24, 18, 12, 10, 15, 22, 10, 8]
    };

    setRecordings([newRec, ...recordings]);
    setSelectedRecordingId(newId);
    setStudioSegment(runCleaner ? 'cleaned' : 'raw');
    setActiveTab('studio');
  };

  const handleSaveEditedTranscript = () => {
    setRecordings(prev => 
      prev.map(r => {
        if (r.id === selectedRecordingId) {
          return studioSegment === 'cleaned'
            ? { ...r, cleaned: editingTextValue }
            : { ...r, raw: editingTextValue };
        }
        return r;
      })
    );
    setIsEditingTranscript(false);
  };

  const formatSeconds = (totalSeconds: number) => {
    const mins = Math.floor(totalSeconds / 60);
    const secs = totalSeconds % 60;
    return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
  };

  // Sub-render: Voice Hub Screen
  const renderVoiceHub = () => {
    const isWhisperDownloaded = models.some(m => m.id === 'whisper-tiny-en' && m.status === 'completed');
    
    return (
      <View style={styles.tabContent}>
        <View style={styles.hubHeader}>
          <Text style={styles.hubTitle}>Your Library</Text>
          <View style={styles.hubModelIndicator}>
            <View style={[styles.glowIndicator, isWhisperDownloaded ? styles.glowActive : styles.glowPending]} />
            <Text style={styles.hubModelText}>
              {isWhisperDownloaded ? 'Whisper Local Ready' : 'Whisper Pending Download'}
            </Text>
          </View>
        </View>

        <FlatList
          data={recordings}
          keyExtractor={(item) => item.id}
          style={styles.recordingsList}
          contentContainerStyle={{ paddingBottom: 100 }}
          ListEmptyComponent={
            <Text style={styles.emptyText}>Your voice library is empty. Start recording below!</Text>
          }
          renderItem={({ item }) => {
            const isSelected = item.id === selectedRecordingId;
            return (
              <RecordingCard
                item={item}
                isSelected={isSelected}
                onPress={() => {
                  setSelectedRecordingId(item.id);
                  setActiveTab('studio');
                }}
              />
            );
          }}
        />

        {/* Large Floating Record FAB */}
        <TouchableOpacity style={styles.recordFab} onPress={startRecordingSim}>
          <Text style={styles.recordFabIcon}>🎤</Text>
        </TouchableOpacity>
      </View>
    );
  };

  // Sub-render: The Studio Screen
  const renderStudio = () => {
    const activeRec = recordings.find(r => r.id === selectedRecordingId);
    
    return (
      <ScrollView style={styles.tabContent} contentContainerStyle={{ paddingBottom: 40 }}>
        <Text style={styles.hubTitle}>The Studio</Text>
        <Text style={styles.studioSubtitle}>Review and format on-device transcripts.</Text>

        {activeRec ? (
          <View style={styles.studioCanvas}>
            <View style={styles.studioCanvasHeader}>
              <View>
                <Text style={styles.activeRecTitle}>{activeRec.title}</Text>
                <Text style={styles.activeRecDate}>{activeRec.date}</Text>
              </View>
              <Text style={styles.studioEngineTag}>Local Cleaner</Text>
            </View>

            {/* Segment Controller (Clean vs Raw) */}
            <View style={styles.segmentContainer}>
              <TouchableOpacity
                style={[styles.segmentButton, studioSegment === 'cleaned' && styles.segmentButtonActive]}
                onPress={() => {
                  setStudioSegment('cleaned');
                  setIsEditingTranscript(false);
                }}
              >
                <Text style={[styles.segmentText, studioSegment === 'cleaned' && styles.segmentTextActive]}>
                  Cleaned Transcript
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.segmentButton, studioSegment === 'raw' && styles.segmentButtonActive]}
                onPress={() => {
                  setStudioSegment('raw');
                  setIsEditingTranscript(false);
                }}
              >
                <Text style={[styles.segmentText, studioSegment === 'raw' && styles.segmentTextActive]}>
                  Raw Transcript
                </Text>
              </TouchableOpacity>
            </View>

            {/* Transcript Panel */}
            <View style={styles.transcriptPanel}>
              {isEditingTranscript ? (
                <View>
                  <TextInput
                    style={styles.transcriptTextInput}
                    value={editingTextValue}
                    onChangeText={setEditingTextValue}
                    multiline={true}
                  />
                  <View style={styles.editActionRow}>
                    <TouchableOpacity
                      style={[styles.studioButton, { backgroundColor: '#dc3545', marginRight: 10 }]}
                      onPress={() => setIsEditingTranscript(false)}
                    >
                      <Text style={styles.studioButtonText}>Cancel</Text>
                    </TouchableOpacity>
                    <TouchableOpacity
                      style={[styles.studioButton, { backgroundColor: '#62f9ee' }]}
                      onPress={handleSaveEditedTranscript}
                    >
                      <Text style={[styles.studioButtonText, { color: '#003734' }]}>Save</Text>
                    </TouchableOpacity>
                  </View>
                </View>
              ) : (
                <View>
                  <Text style={styles.transcriptText}>
                    {studioSegment === 'cleaned' ? activeRec.cleaned : activeRec.raw}
                  </Text>
                  
                  <TouchableOpacity
                    style={styles.editButton}
                    onPress={() => {
                      setIsEditingTranscript(true);
                      setEditingTextValue(studioSegment === 'cleaned' ? activeRec.cleaned : activeRec.raw);
                    }}
                  >
                    <Text style={styles.editButtonText}>✎ Edit Transcript</Text>
                  </TouchableOpacity>
                </View>
              )}
            </View>
          </View>
        ) : (
          <View style={styles.noSelectedCard}>
            <Text style={styles.noSelectedText}>No recording selected. Go to Voice Hub to choose or record one.</Text>
          </View>
        )}

        {/* Dictionary Sandbox testing tool */}
        <View style={styles.studioSandboxCard}>
          <Text style={styles.sandboxTitle}>Dictionary Sandbox</Text>
          <Text style={styles.sandboxInstruction}>
            Type text here and run dictionary cleaner to verify your mappings.
          </Text>
          <TextInput
            style={styles.sandboxInput}
            value={testText}
            onChangeText={setTestText}
            placeholder="Type word to test (e.g., 'Vela is awesome')"
            placeholderTextColor="#859491"
            multiline={true}
          />
          <TouchableOpacity style={styles.sandboxButton} onPress={runCustomClean}>
            <Text style={styles.sandboxButtonText}>Clean Text</Text>
          </TouchableOpacity>

          {testCleanedText !== '' && (
            <View style={styles.sandboxResult}>
              <Text style={styles.sandboxResultTitle}>Output Result:</Text>
              <Text style={styles.sandboxResultText}>{testCleanedText}</Text>
            </View>
          )}
        </View>
      </ScrollView>
    );
  };

  // Sub-render: The Engine Room Screen
  const renderEngineRoom = () => {
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

        {/* Personal Dictionary */}
        <View style={styles.engineCard}>
          <Text style={styles.engineCardTitle}>Personal Dictionary</Text>
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

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#62f9ee" />
        <Text style={styles.loadingText}>Initializing Vela Voice...</Text>
      </View>
    );
  }

  return (
    <SafeAreaView style={styles.container}>
      {/* Top Application Bar */}
      <View style={styles.topAppBar}>
        <View style={styles.brandRow}>
          <Image
            source={require('./assets/logo.png')}
            style={styles.appLogo}
            resizeMode="contain"
          />
          <Text style={styles.appTitle}>VelaVoice</Text>
        </View>
        <View style={styles.appIndicator}>
          <View style={styles.appIndicatorGlow} />
          <Text style={styles.appIndicatorText}>OLED OPTIMIZED</Text>
        </View>
      </View>

      {/* Main Tab Window Content */}
      <View style={styles.contentWindow}>
        {activeTab === 'hub' && renderVoiceHub()}
        {activeTab === 'studio' && renderStudio()}
        {activeTab === 'engine' && renderEngineRoom()}
      </View>

      {/* Recording Screen Simulation Sheet Overlay */}
      {isRecording && (
        <View style={styles.recordingOverlay}>
          <View style={styles.recordingSheet}>
            {/* Brand mark — clean V logo at the top-center of the overlay */}
            <View style={styles.overlayLogoRow}>
              <OverlayLogo size={28} />
            </View>
            <View style={styles.sheetHeader}>
              <View style={styles.recordingDot} />
              <Text style={styles.recordingStateText}>RECORDING AUDIO</Text>
            </View>
            
            <Text style={styles.recordingTimer}>{formatSeconds(recordingSeconds)}</Text>
            
            {/* Visual Realtime waves */}
            <View style={styles.realtimeWaveContainer}>
              {recordingAmplitudes.map((h, i) => (
                <View
                  key={i}
                  style={[styles.realtimeWaveBar, { height: h }]}
                />
              ))}
            </View>

            <View style={styles.sheetActions}>
              <TouchableOpacity
                style={[styles.sheetButton, styles.sheetBtnRaw]}
                onPress={() => stopRecordingSim(false)}
              >
                <Text style={styles.sheetBtnRawText}>Stop Raw</Text>
              </TouchableOpacity>

              <TouchableOpacity
                style={[styles.sheetButton, styles.sheetBtnClean]}
                onPress={() => stopRecordingSim(true)}
              >
                <Text style={styles.sheetBtnCleanText}>Stop & Clean</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      )}

      {/* Bottom Navigation Tab Bar */}
      <View style={styles.tabBar}>
        <TouchableOpacity
          style={[styles.tabItem, activeTab === 'hub' && styles.tabItemActive]}
          onPress={() => setActiveTab('hub')}
        >
          <Text style={[styles.tabIcon, activeTab === 'hub' && styles.tabTextActive]}>🎤</Text>
          <Text style={[styles.tabLabel, activeTab === 'hub' && styles.tabTextActive]}>Voice Hub</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.tabItem, activeTab === 'studio' && styles.tabItemActive]}
          onPress={() => setActiveTab('studio')}
        >
          <Text style={[styles.tabIcon, activeTab === 'studio' && styles.tabTextActive]}>📝</Text>
          <Text style={[styles.tabLabel, activeTab === 'studio' && styles.tabTextActive]}>Studio</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.tabItem, activeTab === 'engine' && styles.tabItemActive]}
          onPress={() => setActiveTab('engine')}
        >
          <Text style={[styles.tabIcon, activeTab === 'engine' && styles.tabTextActive]}>⚙️</Text>
          <Text style={[styles.tabLabel, activeTab === 'engine' && styles.tabTextActive]}>Engine Room</Text>
        </TouchableOpacity>
      </View>

      <StatusBar style="light" backgroundColor="#0e1514" />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0e1514', // Lumina Sonic Dark background
  },
  loadingContainer: {
    flex: 1,
    backgroundColor: '#0e1514',
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    color: '#dde4e2',
    marginTop: 15,
    fontSize: 16,
    fontFamily: Platform.OS === 'ios' ? 'System' : 'sans-serif',
  },
  topAppBar: {
    height: 60,
    backgroundColor: '#0e1514',
    borderBottomWidth: 1,
    borderBottomColor: '#3c4948',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    marginTop: Platform.OS === 'android' ? 25 : 0,
  },
  brandRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  appLogo: {
    width: 28,
    height: 28,
    marginRight: 10,
  },
  appTitle: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#ffffff',
    letterSpacing: -0.5,
  },
  appIndicator: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1a2120',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  appIndicatorGlow: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: '#62f9ee',
    marginRight: 6,
    shadowColor: '#62f9ee',
    shadowOffset: { width: 0, height: 0 },
    shadowOpacity: 0.8,
    shadowRadius: 4,
  },
  appIndicatorText: {
    color: '#bacac7',
    fontSize: 9,
    fontWeight: 'bold',
    letterSpacing: 0.5,
  },
  contentWindow: {
    flex: 1,
  },
  tabContent: {
    flex: 1,
    paddingHorizontal: 20,
    paddingTop: 15,
  },
  hubHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 20,
  },
  hubTitle: {
    fontSize: 26,
    fontWeight: 'bold',
    color: '#ffffff',
  },
  hubModelIndicator: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#161d1c',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  glowIndicator: {
    width: 6,
    height: 6,
    borderRadius: 3,
    marginRight: 6,
  },
  glowActive: {
    backgroundColor: '#62f9ee',
  },
  glowPending: {
    backgroundColor: '#ffb4ab',
  },
  hubModelText: {
    color: '#dde4e2',
    fontSize: 11,
  },
  recordingsList: {
    flex: 1,
  },
  emptyText: {
    color: '#859491',
    fontSize: 14,
    textAlign: 'center',
    marginTop: 40,
    lineHeight: 20,
  },
  recordingCard: {
    backgroundColor: '#161d1c', // Low container background
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  recordingCardSelected: {
    borderColor: '#62f9ee', // Active outline
  },
  recCardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 10,
  },
  recTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#ffffff',
  },
  recDate: {
    fontSize: 12,
    color: '#859491',
    marginTop: 2,
  },
  recChevron: {
    fontSize: 16,
    color: '#859491',
  },
  recWaveContainer: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    height: 30,
    marginTop: 4,
  },
  recWaveBar: {
    width: 3,
    marginRight: 2,
    borderRadius: 1.5,
  },
  recordFab: {
    position: 'absolute',
    bottom: 25,
    alignSelf: 'center',
    backgroundColor: '#62f9ee',
    width: 68,
    height: 68,
    borderRadius: 34,
    justifyContent: 'center',
    alignItems: 'center',
    shadowColor: '#62f9ee',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 10,
    elevation: 8,
  },
  recordFabIcon: {
    fontSize: 28,
    color: '#003734',
  },

  // Studio Screen styles
  studioSubtitle: {
    fontSize: 14,
    color: '#859491',
    marginTop: -16,
    marginBottom: 20,
  },
  studioCanvas: {
    backgroundColor: '#161d1c',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#3c4948',
    padding: 16,
    marginBottom: 20,
  },
  studioCanvasHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 15,
    borderBottomWidth: 1,
    borderBottomColor: '#3c4948',
    paddingBottom: 12,
  },
  activeRecTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#ffffff',
  },
  activeRecDate: {
    fontSize: 12,
    color: '#859491',
    marginTop: 2,
  },
  studioEngineTag: {
    backgroundColor: '#622599',
    color: '#d1a1ff',
    fontSize: 10,
    fontWeight: 'bold',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
    overflow: 'hidden',
  },
  segmentContainer: {
    flexDirection: 'row',
    backgroundColor: '#0e1514',
    padding: 4,
    borderRadius: 8,
    marginBottom: 15,
  },
  segmentButton: {
    flex: 1,
    paddingVertical: 8,
    alignItems: 'center',
    borderRadius: 6,
  },
  segmentButtonActive: {
    backgroundColor: '#1a2120',
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  segmentText: {
    color: '#859491',
    fontSize: 13,
  },
  segmentTextActive: {
    color: '#62f9ee',
    fontWeight: 'bold',
  },
  transcriptPanel: {
    backgroundColor: '#0e1514',
    padding: 14,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  transcriptText: {
    color: '#dde4e2',
    fontSize: 15,
    lineHeight: 22,
  },
  editButton: {
    marginTop: 15,
    alignSelf: 'flex-end',
  },
  editButtonText: {
    color: '#62f9ee',
    fontSize: 13,
    fontWeight: '500',
  },
  transcriptTextInput: {
    color: '#dde4e2',
    fontSize: 15,
    lineHeight: 22,
    minHeight: 120,
    textAlignVertical: 'top',
  },
  editActionRow: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    marginTop: 12,
  },
  studioButton: {
    paddingVertical: 6,
    paddingHorizontal: 16,
    borderRadius: 6,
  },
  studioButtonText: {
    fontSize: 13,
    fontWeight: 'bold',
    color: '#ffffff',
  },
  noSelectedCard: {
    backgroundColor: '#161d1c',
    padding: 24,
    borderRadius: 12,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#3c4948',
    marginBottom: 20,
  },
  noSelectedText: {
    color: '#859491',
    fontSize: 14,
    textAlign: 'center',
  },
  studioSandboxCard: {
    backgroundColor: '#161d1c',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#3c4948',
    padding: 16,
  },
  sandboxTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#ffffff',
    marginBottom: 4,
  },
  sandboxInstruction: {
    fontSize: 12,
    color: '#859491',
    marginBottom: 12,
  },
  sandboxInput: {
    backgroundColor: '#0e1514',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#3c4948',
    padding: 10,
    color: '#dde4e2',
    fontSize: 14,
    height: 70,
    textAlignVertical: 'top',
    marginBottom: 12,
  },
  sandboxButton: {
    backgroundColor: '#1a2120',
    paddingVertical: 10,
    borderRadius: 8,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  sandboxButtonText: {
    color: '#62f9ee',
    fontWeight: 'bold',
    fontSize: 14,
  },
  sandboxResult: {
    marginTop: 15,
    backgroundColor: '#1a2120',
    padding: 12,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  sandboxResultTitle: {
    fontSize: 12,
    fontWeight: 'bold',
    color: '#ddb7ff',
    marginBottom: 4,
  },
  sandboxResultText: {
    fontSize: 14,
    color: '#dde4e2',
    lineHeight: 20,
  },

  // Engine Room styles
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

  // Simulated Recording sheet overlay styles
  recordingOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(14, 21, 20, 0.85)',
    justifyContent: 'flex-end',
    zIndex: 999,
  },
  recordingSheet: {
    backgroundColor: '#161d1c',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    padding: 24,
    borderTopWidth: 1,
    borderTopColor: '#62f9ee',
    alignItems: 'center',
  },
  overlayLogoRow: {
    alignItems: 'center',
    marginBottom: 8,
    marginTop: -4,
  },
  sheetHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 10,
  },
  recordingDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#ffb4ab',
    marginRight: 6,
  },
  recordingStateText: {
    color: '#ffb4ab',
    fontSize: 10,
    fontWeight: 'bold',
    letterSpacing: 1,
  },
  recordingTimer: {
    fontSize: 40,
    fontWeight: 'bold',
    color: '#ffffff',
    marginBottom: 20,
  },
  realtimeWaveContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 60,
    marginBottom: 30,
    width: '100%',
    justifyContent: 'center',
  },
  realtimeWaveBar: {
    width: 4,
    backgroundColor: '#62f9ee',
    marginHorizontal: 2,
    borderRadius: 2,
  },
  sheetActions: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    width: '100%',
  },
  sheetButton: {
    flex: 1,
    paddingVertical: 12,
    borderRadius: 8,
    alignItems: 'center',
    marginHorizontal: 8,
  },
  sheetBtnRaw: {
    backgroundColor: '#1a2120',
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  sheetBtnRawText: {
    color: '#dde4e2',
    fontWeight: 'bold',
    fontSize: 14,
  },
  sheetBtnClean: {
    backgroundColor: '#62f9ee',
  },
  sheetBtnCleanText: {
    color: '#003734',
    fontWeight: 'bold',
    fontSize: 14,
  },

  // Bottom Navigation Bar styles
  tabBar: {
    height: 70,
    backgroundColor: '#161d1c',
    borderTopWidth: 1,
    borderTopColor: '#3c4948',
    flexDirection: 'row',
    justifyContent: 'space-around',
    paddingBottom: Platform.OS === 'ios' ? 15 : 5,
    paddingTop: 8,
  },
  tabItem: {
    alignItems: 'center',
    justifyContent: 'center',
    flex: 1,
  },
  tabItemActive: {
    borderTopWidth: 0,
  },
  tabIcon: {
    fontSize: 18,
    color: '#859491',
    marginBottom: 4,
  },
  tabLabel: {
    fontSize: 11,
    color: '#859491',
  },
  tabTextActive: {
    color: '#62f9ee', //Active teal accent
    fontWeight: 'bold',
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
});
