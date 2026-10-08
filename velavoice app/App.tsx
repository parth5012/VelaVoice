import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  StyleSheet,
  Text,
  View,
  Image,
  ActivityIndicator,
  TouchableOpacity,
  AppState,
  AppStateStatus,
  NativeModules,
  Platform,
  PermissionsAndroid,
  SafeAreaView,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { ModelManager, ModelInfo } from './src/services/ModelManager';
import OverlayLogo from './src/components/OverlayLogo';
import { Recording } from './src/components/RecordingCard';
import { buildScribeDrafts } from './src/services/scribeSimulator';
import { cleanWithDictionary } from './src/utils/dictionaryClean';
import { isQuarantinedEntry } from './src/utils/quarantineBadge';
import { getGeminiApiKey } from './src/services/GeminiService';
import { installHttpOverrides } from './src/services/installHttpOverrides';
import { useDictionaryKeywords } from './src/hooks/useDictionaryKeywords';
import { useSyncToDrive } from './src/hooks/useSyncToDrive';
import { useRecordingSim } from './src/hooks/useRecordingSim';
import { EngineRoomScreen } from './src/components/engine/EngineRoomScreen';
import { HubScreen } from './src/components/hub/HubScreen';
import { StudioScreen } from './src/components/studio/StudioScreen';

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

  const {
    dictionary,
    originalWord,
    setOriginalWord,
    replacement,
    setReplacement,
    keywords,
    keywordInput,
    setKeywordInput,
    loadDictionary,
    loadKeywords,
    handleAddEntry,
    handleDeleteEntry,
    handleAddKeyword,
    handleDeleteKeyword,
  } = useDictionaryKeywords();

  const {
    driveConfigured,
    isSyncing,
    syncResult,
    unsyncedCount,
    totalTranscriptions,
    recentTranscriptions,
    initDriveCredentials,
    refreshDriveStatus,
    handleSyncToDrive,
  } = useSyncToDrive();

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

  const { isRecording, recordingSeconds, recordingAmplitudes, startRecordingSim, stopRecordingSim } =
    useRecordingSim({
      requestMicPermission,
      recordings,
      onRecordingFinished: (newRec, runCleaner) => {
        setRecordings([newRec, ...recordings]);
        setSelectedRecordingId(newRec.id);
        setStudioSegment(runCleaner ? 'cleaned' : 'raw');
        setActiveTab('studio');
      },
    });

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
        {activeTab === 'hub' && (
          <HubScreen
            models={models}
            recordings={recordings}
            selectedRecordingId={selectedRecordingId}
            onSelectRecording={(id) => {
              setSelectedRecordingId(id);
              setActiveTab('studio');
            }}
            onStartRecording={startRecordingSim}
          />
        )}
        {activeTab === 'studio' && (
          <StudioScreen
            recordings={recordings}
            selectedRecordingId={selectedRecordingId}
            studioSegment={studioSegment}
            isEditingTranscript={isEditingTranscript}
            scribeStyle={scribeStyle}
            scribeInstruction={scribeInstruction}
            scribeDrafts={scribeDrafts}
            selectedScribeDraftIndex={selectedScribeDraftIndex}
            isGeneratingScribe={isGeneratingScribe}
            scribeAppName={scribeAppName}
            scribeInputType={scribeInputType}
            showPromptDebug={showPromptDebug}
            testText={testText}
            testCleanedText={testCleanedText}
            setStudioSegment={setStudioSegment}
            setIsEditingTranscript={setIsEditingTranscript}
            setEditingTextValue={setEditingTextValue}
            setScribeStyle={setScribeStyle}
            setScribeInstruction={setScribeInstruction}
            setScribeDrafts={setScribeDrafts}
            setSelectedScribeDraftIndex={setSelectedScribeDraftIndex}
            setScribeAppName={setScribeAppName}
            setScribeInputType={setScribeInputType}
            setShowPromptDebug={setShowPromptDebug}
            setTestText={setTestText}
            runScribeRewrite={runScribeRewrite}
            commitScribeDraft={commitScribeDraft}
            runCustomClean={runCustomClean}
            handleSaveCorrection={handleSaveCorrection}
          />
        )}
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
