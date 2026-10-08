import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  StyleSheet,
  Text,
  View,
  Image,
  FlatList,
  ActivityIndicator,
  TouchableOpacity,
  TextInput,
  AppState,
  AppStateStatus,
  NativeModules,
  Platform,
  PermissionsAndroid,
  ScrollView,
  SafeAreaView,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import Constants from 'expo-constants';
import { ModelManager, ModelInfo, DictionaryEntry, DictionaryKeyword } from './src/services/ModelManager';
import OverlayLogo from './src/components/OverlayLogo';
import { Recording, RecordingCard } from './src/components/RecordingCard';
import { buildScribeDrafts } from './src/services/scribeSimulator';
import { cleanWithDictionary } from './src/utils/dictionaryClean';
import { TranscriptionEditor } from './src/components/TranscriptionEditor';
import { isQuarantinedEntry } from './src/utils/quarantineBadge';
import GeminiSettings from './src/components/GeminiSettings';
import { getGeminiApiKey } from './src/services/GeminiService';
import { installHttpOverrides } from './src/services/installHttpOverrides';
import { EngineRoomScreen } from './src/components/engine/EngineRoomScreen';

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
  const [streamingMode, setStreamingMode] = useState<string>('instant');
  const [groqApiKey, setGroqApiKey] = useState<string>('');
  const [groqModel, setGroqModel] = useState<string>('whisper-large-v3');
  const [openaiApiKey, setOpenaiApiKey] = useState<string>('');
  const [openaiModel, setOpenaiModel] = useState<string>('whisper-1');
  const [openaiEndpoint, setOpenaiEndpoint] = useState<string>('https://api.openai.com/v1');
  const [geminiApiKey, setGeminiApiKey] = useState<string>('');

  const updatePreference = async (key: string, value: string, setter: (val: string) => void) => {
    setter(value);
    if (NativeModules.ModelVerifier) {
      try {
        await NativeModules.ModelVerifier.setStringPreference(key, value);
      } catch (e) {
        console.error(`Failed to save preference ${key}`, );
      }
    }
  };

  // Personal Dictionary States
  const [dictionary, setDictionary] = useState<DictionaryEntry[]>([]);
  const [originalWord, setOriginalWord] = useState('');
  const [replacement, setReplacement] = useState('');
  const [language, setLanguage] = useState('');
  const [priority, setPriority] = useState('1');

  // Dictionary Keywords States
  const [keywords, setKeywords] = useState<DictionaryKeyword[]>([]);
  const [keywordInput, setKeywordInput] = useState('');

  // Google Drive Sync States
  const [driveConfigured, setDriveConfigured] = useState(false);
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncResult, setSyncResult] = useState<string | null>(null);
  const [unsyncedCount, setUnsyncedCount] = useState(0);
  const [totalTranscriptions, setTotalTranscriptions] = useState(0);
  const [recentTranscriptions, setRecentTranscriptions] = useState<any[]>([]);

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

  // Scribe Simulator states
  const [scribeStyle, setScribeStyle] = useState<string>('Professional');
  const [scribeInstruction, setScribeInstruction] = useState<string>('');
  const [scribeDrafts, setScribeDrafts] = useState<string[]>([]);
  const [selectedScribeDraftIndex, setSelectedScribeDraftIndex] = useState<number>(0);
  const [isGeneratingScribe, setIsGeneratingScribe] = useState<boolean>(false);
  const [scribeAppName, setScribeAppName] = useState<string>('com.slack');
  const [scribeInputType, setScribeInputType] = useState<string>('Message Field');
  const [showPromptDebug, setShowPromptDebug] = useState<boolean>(false);

  useEffect(() => {
    installHttpOverrides();

    loadModels();
    loadDictionary();
    loadKeywords();
    checkAccessibilityStatus();
    checkMicPermission();
    initDriveCredentials();
    refreshDriveStatus();

    const subscription = AppState.addEventListener('change', (nextAppState: AppStateStatus) => {
      if (nextAppState === 'active') {
        checkAccessibilityStatus();
        checkMicPermission();
        refreshDriveStatus();
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

  const loadKeywords = useCallback(async () => {
    try {
      const list = await ModelManager.getKeywords();
      setKeywords(list);
    } catch (e) {
      console.error('Failed to load keywords', e);
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

  const handleAddKeyword = useCallback(async () => {
    const trimmed = keywordInput.trim();
    if (!trimmed) {
      alert('Please enter a keyword.');
      return;
    }
    // Validate that keyword contains only valid word characters
    if (!/^[\w\s-]+$/.test(trimmed)) {
      alert('Keywords can only contain letters, numbers, spaces, and hyphens.');
      return;
    }
    try {
      await ModelManager.addKeyword(trimmed, null);
      setKeywordInput('');
      await loadKeywords();
    } catch (e) {
      console.error('Failed to add keyword', e);
      alert('Failed to add keyword. It might already exist.');
    }
  }, [keywordInput, loadKeywords]);

  const handleDeleteKeyword = useCallback(async (id?: number) => {
    if (id === undefined) return;
    try {
      await ModelManager.deleteKeyword(id);
      await loadKeywords();
    } catch (e) {
      console.error('Failed to delete keyword', e);
      alert('Failed to delete keyword.');
    }
  }, [loadKeywords]);

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
          setStreamingMode(prefsJson.streamingMode || 'instant');
          setGroqApiKey(prefsJson.groqApiKey || '');
          setGroqModel(prefsJson.groqModel || 'whisper-large-v3');
          setOpenaiApiKey(prefsJson.openaiApiKey || '');
          setOpenaiModel(prefsJson.openaiModel || 'whisper-1');
          setOpenaiEndpoint(prefsJson.openaiEndpoint || 'https://api.openai.com/v1');
      if (prefsJson.geminiApiKey) setGeminiApiKey(prefsJson.geminiApiKey);
      getGeminiApiKey().then((k) => { if (k) setGeminiApiKey(k); });
        }
    } catch (e) {
      console.error('Failed to load preferences', e);
    }
  }
  }, []);

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

  // ──────────────────────────────────────────────
  // Google Drive Sync
  // ──────────────────────────────────────────────

  const initDriveCredentials = async () => {
    if (!NativeModules.GoogleDriveSync) return;
    try {
      // Check if credentials are already stored in native prefs
      const hasCreds = await NativeModules.GoogleDriveSync.hasDriveCredentials();
      if (!hasCreds) {
        // Read from Constants.extra (loaded from .env) and store in native prefs
        const extras = Constants.expoConfig?.extra || {};
        const clientId = extras.googleDriveClientId;
        const clientSecret = extras.googleDriveClientSecret;
        const refreshToken = extras.googleDriveRefreshToken;
        if (clientId && clientSecret && refreshToken) {
          await NativeModules.GoogleDriveSync.setDriveCredentials(
            clientId,
            clientSecret,
            refreshToken
          );
        }
      }
    } catch (e) {
      console.error('Failed to init Drive credentials', e);
    }
  };

  const refreshDriveStatus = async () => {
    if (!NativeModules.GoogleDriveSync) return;
    try {
      const hasCreds = await NativeModules.GoogleDriveSync.hasDriveCredentials();
      setDriveConfigured(hasCreds);
      if (hasCreds) {
        const unsynced = await NativeModules.GoogleDriveSync.getUnsyncedCount();
        setUnsyncedCount(unsynced);
        const total = await NativeModules.GoogleDriveSync.getTotalTranscriptionCount();
        setTotalTranscriptions(total);
      }
      if (NativeModules.GoogleDriveSync.getRecentTranscriptions) {
        const recentJson = await NativeModules.GoogleDriveSync.getRecentTranscriptions(20);
        const parsed = JSON.parse(recentJson);
        setRecentTranscriptions(parsed);
      }
    } catch (e) {
      console.error('Failed refresh Drive status', e);
    }
  };

  const handleSyncToDrive = async () => {
    if (!NativeModules.GoogleDriveSync || isSyncing) return;
    setIsSyncing(true);
    setSyncResult(null);
    try {
      const resultJson = await NativeModules.GoogleDriveSync.syncToDrive();
      const result = typeof resultJson === 'string' ? JSON.parse(resultJson) : resultJson;
      const uploaded = result.uploaded || 0;
      const failed = result.failed || 0;
      if (uploaded > 0) {
        setSyncResult(`✅ Synced ${uploaded} transcription${uploaded > 1 ? 's' : ''} to Google Drive.`);
      } else if (result === 'No new transcriptions to sync.') {
        setSyncResult('✅ All transcriptions already synced.');
      } else {
        setSyncResult(`Synced: ${uploaded}, Failed: ${failed}`);
      }
      await refreshDriveStatus();
    } catch (e: any) {
      const msg = e?.message || String(e);
      if (msg.includes('NO_CREDENTIALS')) {
        setSyncResult('⚠️ Drive credentials not configured. Check your .env file.');
      } else if (msg.includes('AUTH_FAILED')) {
        setSyncResult('⚠️ Authentication failed. Your Google Drive token may be expired.');
      } else {
        setSyncResult(`❌ Sync failed: ${msg}`);
      }
    } finally {
      setIsSyncing(false);
    }
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
    setTestCleanedText(cleanWithDictionary(testText, dictionary, keywords));
  };

  // simulated Scribe engine prompt builder and draft generator
  const runScribeRewrite = () => {
    const activeRec = recordings.find(r => r.id === selectedRecordingId);
    if (!activeRec) return;

    const baseText = studioSegment === 'cleaned' ? activeRec.cleaned : activeRec.raw;
    if (!baseText.trim()) return;

    setIsGeneratingScribe(true);

    // Simulate mobile LLM inference latency (800ms)
    setTimeout(() => {
      setScribeDrafts(buildScribeDrafts(baseText, scribeStyle, scribeInstruction));
      setSelectedScribeDraftIndex(0);
      setIsGeneratingScribe(false);
    }, 800);
  };

  const commitScribeDraft = () => {
    if (scribeDrafts.length === 0) return;
    const selectedDraft = scribeDrafts[selectedScribeDraftIndex];
    if (!selectedDraft) return;

    setRecordings(prev => prev.map(r => {
      if (r.id === selectedRecordingId) {
        return studioSegment === 'cleaned'
          ? { ...r, cleaned: selectedDraft }
          : { ...r, raw: selectedDraft };
      }
      return r;
    }));
    
    setEditingTextValue(selectedDraft);
    alert('Scribe draft committed to transcript!');
    setScribeDrafts([]);
    setScribeInstruction('');
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

  const handleSaveCorrection = async (
    audioId: string,
    original: string,
    corrected: string,
    edits: any[],
    editDistance: number,
    scribeStyle?: string,
    quarantined?: boolean
  ) => {
    void scribeStyle;
    setRecordings(prev =>
      prev.map(r => {
        if (r.id === audioId) {
          return studioSegment === 'cleaned'
            ? { ...r, cleaned: corrected }
            : { ...r, raw: corrected };
        }
        return r;
      })
    );

    // Quarantine egress block (ticket #136): the local edit stays on-device,
    // but nothing is POSTed and nothing enters correction training. The flag
    // arrives in the 7th slot (TranscriptionEditor passes scribeStyle 6th);
    // the library entry verdict is a defense-in-depth fallback so a stale
    // caller that drops the flag still cannot egress quarantined content.
    const isQuarantined =
      quarantined === true || isQuarantinedEntry(recordings.find(r => r.id === audioId));
    if (isQuarantined) {
      setIsEditingTranscript(false);
      return;
    }

    try {
      const response = await fetch('https://api.velavoice.com/save_correction', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          audio_id: audioId,
          original_transcription: original,
          corrected_transcription: corrected,
          edits,
          edit_distance: editDistance,
          user_id: 'local_user',
          confidence_score: 1.0
        })
      });
      if (!response.ok) {
        throw new Error('API server returned error status');
      }
    } catch (err) {
      console.warn('Failed to save correction to API backend:', err);
    }

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

        {activeRec ? (<>
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
          <TranscriptionEditor
            audioId={activeRec.id}
            originalTranscription={studioSegment === 'cleaned' ? activeRec.cleaned : activeRec.raw}
            onSave={handleSaveCorrection}
            onCancel={() => setIsEditingTranscript(false)}
            quarantined={activeRec.quarantined}
          />
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

      {/* Scribe (On-Device LLM Re-writer) Prototype */}
      <View style={styles.studioScribeCard}>
        <Text style={styles.sandboxTitle}>Scribe Assistant (Llama-3)</Text>
        <Text style={styles.sandboxInstruction}>
          Select a rewrite style, app context, and enter instructions to dynamically transform the transcript using local LLM inference.
        </Text>

        {/* App & Field Context Simulators */}
        <Text style={styles.scribeSectionLabel}>Simulate App & Field Context</Text>
        <View style={styles.scribeContextRow}>
          <View style={{ flex: 1, marginRight: 8 }}>
            <Text style={styles.scribeSubLabel}>App Name / ID</Text>
            <View style={styles.scribeDropdownContainer}>
              {['com.slack', 'com.google.android.gm', 'com.whatsapp'].map((app) => (
                <TouchableOpacity
                  key={app}
                  style={[styles.scribeContextChip, scribeAppName === app && styles.scribeContextChipActive]}
                  onPress={() => setScribeAppName(app)}
                >
                  <Text style={[styles.scribeContextChipText, scribeAppName === app && styles.scribeContextChipTextActive]}>
                    {app === 'com.slack' ? 'Slack' : app === 'com.google.android.gm' ? 'Gmail' : 'WhatsApp'}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          </View>

          <View style={{ flex: 1 }}>
            <Text style={styles.scribeSubLabel}>Input Field Variant</Text>
            <View style={styles.scribeDropdownContainer}>
              {['Message Field', 'Email Field', 'Search Bar'].map((field) => (
                <TouchableOpacity
                  key={field}
                  style={[styles.scribeContextChip, scribeInputType === field && styles.scribeContextChipActive]}
                  onPress={() => setScribeInputType(field)}
                >
                  <Text style={[styles.scribeContextChipText, scribeInputType === field && styles.scribeContextChipTextActive]}>
                    {field}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          </View>
        </View>

        {/* Style Chips select */}
        <Text style={styles.scribeSectionLabel}>Select Rewrite Style</Text>
        <View style={styles.scribeStyleContainer}>
          {['Professional', 'Casual', 'Bullet Points', 'Email Draft', 'Proofread'].map((styleOpt) => (
            <TouchableOpacity
              key={styleOpt}
              style={[styles.scribeStyleChip, scribeStyle === styleOpt && styles.scribeStyleChipActive]}
              onPress={() => setScribeStyle(styleOpt)}
            >
              <Text style={[styles.scribeStyleChipText, scribeStyle === styleOpt && styles.scribeStyleChipTextActive]}>
                {styleOpt}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* Custom Instructions */}
        <Text style={styles.scribeSectionLabel}>Custom Instructions / Intent (Optional)</Text>
        <TextInput
          style={styles.sandboxInput}
          value={scribeInstruction}
          onChangeText={setScribeInstruction}
          placeholder="e.g. 'translate to Spanish', 'keep it short', 'sound excited'"
          placeholderTextColor="#859491"
        />

        {/* Run Scribe button */}
        <TouchableOpacity 
          style={[styles.sandboxButton, { backgroundColor: '#161d1c', borderColor: '#62f9ee', marginTop: 10 }]} 
          onPress={runScribeRewrite}
          disabled={isGeneratingScribe}
        >
          {isGeneratingScribe ? (
            <ActivityIndicator size="small" color="#62f9ee" />
          ) : (
            <Text style={[styles.sandboxButtonText, { color: '#62f9ee' }]}>Generate Scribe Options</Text>
          )}
        </TouchableOpacity>

        {/* Draft Options results renderer */}
        {scribeDrafts.length > 0 && (
          <View style={styles.scribeResultsContainer}>
            <Text style={styles.sandboxResultTitle}>Simulated On-Device LLM Options:</Text>
            <Text style={styles.sandboxInstruction}>
              Compare multiple drafts below. Tap one to select, then commit it to update the transcript file.
            </Text>
            
            {scribeDrafts.map((draft, idx) => (
              <TouchableOpacity
                key={idx}
                style={[styles.draftOptionCard, selectedScribeDraftIndex === idx && styles.draftOptionCardActive]}
                onPress={() => setSelectedScribeDraftIndex(idx)}
              >
                <View style={styles.draftCardHeader}>
                  <Text style={[styles.draftTitleText, selectedScribeDraftIndex === idx && styles.draftTitleTextActive]}>
                    Option Draft {idx + 1}
                  </Text>
                  {selectedScribeDraftIndex === idx && (
                    <Text style={{ color: '#62f9ee', fontSize: 12, fontWeight: 'bold' }}>✓ Selected</Text>
                  )}
                </View>
                <Text style={styles.draftBodyText}>{draft}</Text>
              </TouchableOpacity>
            ))}

            <View style={styles.scribeActionRow}>
              <TouchableOpacity 
                style={[styles.scribeActionBtn, { backgroundColor: '#1e3a34', borderColor: '#4e9a86' }]} 
                onPress={commitScribeDraft}
              >
                <Text style={[styles.scribeActionBtnText, { color: '#62f9ee' }]}>Commit to Transcript</Text>
              </TouchableOpacity>
              <TouchableOpacity 
                style={[styles.scribeActionBtn, { backgroundColor: '#1a2120', borderColor: '#3c4948' }]} 
                onPress={() => setScribeDrafts([])}
              >
                <Text style={[styles.scribeActionBtnText, { color: '#859491' }]}>Cancel / Clear</Text>
              </TouchableOpacity>
            </View>
          </View>
        )}

        {/* Debug Prompt Inspector */}
        <TouchableOpacity 
          style={styles.debugToggleHeader} 
          onPress={() => setShowPromptDebug(!showPromptDebug)}
        >
          <Text style={styles.debugToggleText}>
            {showPromptDebug ? '▼ Hide Llama-3 Prompt context (JNI Packaging debug)' : '▶ Show Llama-3 Prompt context (JNI Packaging debug)'}
          </Text>
        </TouchableOpacity>

        {showPromptDebug && (
          <View style={styles.debugContainer}>
            <Text style={styles.debugCodeLabel}>Unified JNI Prompt Package sent to local weights:</Text>
            <ScrollView style={styles.debugScrollView} nestedScrollEnabled={true}>
              <Text style={styles.debugOutputText}>
                {`<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n`}
                {`Scribe: on-device keyboard writing assistant.\n`}
                {`Task: Rewrite user's raw voice input based on style: ${scribeStyle}\n`}
                {`App Name/ID: ${scribeAppName} (${scribeAppName === 'com.slack' ? 'Slack' : scribeAppName === 'com.google.android.gm' ? 'Gmail' : 'WhatsApp'})\n`}
                {`Input Type: ${scribeInputType}\n`}
                {`Preceding Context: ${studioSegment === 'cleaned' ? 'Appended editor text' : 'None'}\n`}
                {`Following Context: None\n\n`}
                {`Style Instruction: ${
                  scribeStyle === 'Professional' ? 'Rewrite input to be formal, professional, polite, and grammatically perfect. Retain the core meaning.' :
                  scribeStyle === 'Casual' ? 'Rewrite input to be casual, friendly, natural, and conversational.' :
                  scribeStyle === 'Bullet Points' ? 'Summarize input into a clear, concise bullet-point list.' :
                  scribeStyle === 'Email Draft' ? 'Draft a professional email based on the brief notes provided, including a subject line and greeting.' :
                  'Fix any spelling, grammar, and punctuation mistakes without changing the style or structure.'
                }\n`}
                {scribeInstruction.trim() ? `Additional Context/intent: ${scribeInstruction.trim()}\n` : ''}
                {`Only output the rewritten text. Do not include introductory phrases, conversational fillers, or explanations. Keep the original language.\n`}
                {`<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n`}
                {`Raw Input: ${studioSegment === 'cleaned' ? activeRec.cleaned : activeRec.raw}\n`}
                {`<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n`}
                {`[LLM Weights Response Drafts generated locally on-device]`}
              </Text>
            </ScrollView>
          </View>
        )}
      </View>
      </>
      ) : (
          <View style={styles.noSelectedCard}>
            <Text style={styles.noSelectedText}>No recording selected. Go to Voice Hub to choose or record one.</Text>
          </View>
        )}

        {/* Dictionary Sandbox testing tool */}
        <View style={styles.studioSandboxCard}>
          <Text style={styles.sandboxTitle}>Dictionary Sandbox</Text>
          <Text style={styles.sandboxInstruction}>
            Type text here and run dictionary cleaner to verify your mappings and keyword protection.
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
        {activeTab === 'engine' && (
          <EngineRoomScreen
            models={models}
            downloadProgress={downloadProgress}
            hasMicPermission={hasMicPermission}
            isAccessibilityEnabled={isAccessibilityEnabled}
            useLlmCleaner={useLlmCleaner}
            transcriptionMode={transcriptionMode}
            streamingMode={streamingMode}
            groqApiKey={groqApiKey}
            groqModel={groqModel}
            openaiApiKey={openaiApiKey}
            openaiModel={openaiModel}
            openaiEndpoint={openaiEndpoint}
            driveConfigured={driveConfigured}
            isSyncing={isSyncing}
            syncResult={syncResult}
            unsyncedCount={unsyncedCount}
            totalTranscriptions={totalTranscriptions}
            recentTranscriptions={recentTranscriptions}
            dictionary={dictionary}
            keywords={keywords}
            keywordInput={keywordInput}
            originalWord={originalWord}
            replacement={replacement}
            updatePreference={updatePreference}
            setTranscriptionMode={setTranscriptionMode}
            setStreamingMode={setStreamingMode}
            setGroqApiKey={setGroqApiKey}
            setGroqModel={setGroqModel}
            setOpenaiApiKey={setOpenaiApiKey}
            setOpenaiModel={setOpenaiModel}
            setOpenaiEndpoint={setOpenaiEndpoint}
            requestMicPermission={requestMicPermission}
            handleOpenAccessibilitySettings={handleOpenAccessibilitySettings}
            handleToggleLlm={handleToggleLlm}
            handleSyncToDrive={handleSyncToDrive}
            handleDownload={handleDownload}
            handleDelete={handleDelete}
            handleAddKeyword={handleAddKeyword}
            handleDeleteKeyword={handleDeleteKeyword}
            handleAddEntry={handleAddEntry}
            handleDeleteEntry={handleDeleteEntry}
            setKeywordInput={setKeywordInput}
            setOriginalWord={setOriginalWord}
            setReplacement={setReplacement}
            onGeminiApiKeyChange={(key) => {
              setGeminiApiKey(key);
              updatePreference('geminiApiKey', key, setGeminiApiKey);
            }}
          />
        )}
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

  // Scribe (On-Device LLM Re-writer) Prototype styles
  studioScribeCard: {
    backgroundColor: '#161d1c',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  scribeSectionLabel: {
    fontSize: 12,
    fontWeight: 'bold',
    color: '#62f9ee',
    marginTop: 12,
    marginBottom: 6,
    letterSpacing: 0.3,
  },
  scribeContextRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    marginBottom: 4,
  },
  scribeSubLabel: {
    fontSize: 11,
    color: '#859491',
    marginBottom: 4,
  },
  scribeDropdownContainer: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  scribeContextChip: {
    backgroundColor: '#1a2120',
    paddingVertical: 5,
    paddingHorizontal: 8,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: '#3c4948',
    marginRight: 6,
    marginBottom: 6,
  },
  scribeContextChipActive: {
    borderColor: '#62f9ee',
    backgroundColor: '#1e3a34',
  },
  scribeContextChipText: {
    fontSize: 11,
    color: '#859491',
  },
  scribeContextChipTextActive: {
    color: '#62f9ee',
  },
  scribeStyleContainer: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  scribeStyleChip: {
    backgroundColor: '#1a2120',
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#3c4948',
    marginRight: 8,
    marginBottom: 8,
  },
  scribeStyleChipActive: {
    borderColor: '#62f9ee',
    backgroundColor: '#1e3a34',
  },
  scribeStyleChipText: {
    fontSize: 12,
    color: '#859491',
  },
  scribeStyleChipTextActive: {
    color: '#62f9ee',
    fontWeight: 'bold',
  },
  scribeResultsContainer: {
    marginTop: 14,
  },
  draftOptionCard: {
    backgroundColor: '#1a2120',
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  draftOptionCardActive: {
    borderColor: '#62f9ee',
    backgroundColor: '#1e3a34',
  },
  draftCardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 6,
  },
  draftTitleText: {
    fontSize: 12,
    fontWeight: 'bold',
    color: '#859491',
  },
  draftTitleTextActive: {
    color: '#62f9ee',
  },
  draftBodyText: {
    fontSize: 14,
    color: '#dde4e2',
    lineHeight: 20,
  },
  scribeActionRow: {
    flexDirection: 'row',
    marginTop: 8,
  },
  scribeActionBtn: {
    flex: 1,
    paddingVertical: 10,
    borderRadius: 8,
    borderWidth: 1,
    alignItems: 'center',
    marginRight: 8,
  },
  scribeActionBtnText: {
    fontSize: 13,
    fontWeight: 'bold',
  },
  debugToggleHeader: {
    marginTop: 14,
    paddingVertical: 8,
  },
  debugToggleText: {
    fontSize: 12,
    color: '#62f9ee',
  },
  debugContainer: {
    backgroundColor: '#0a0f0e',
    borderRadius: 8,
    padding: 10,
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  debugCodeLabel: {
    fontSize: 11,
    fontWeight: 'bold',
    color: '#ddb7ff',
    marginBottom: 6,
  },
  debugScrollView: {
    maxHeight: 180,
  },
  debugOutputText: {
    fontSize: 11,
    fontFamily: 'monospace',
    color: '#a8c0bd',
    lineHeight: 16,
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
});
