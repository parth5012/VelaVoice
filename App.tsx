import React, { useEffect, useState } from 'react';
import {
  StyleSheet,
  Text,
  View,
  FlatList,
  ActivityIndicator,
  TouchableOpacity,
  Switch,
  TextInput,
  AppState,
  AppStateStatus,
  NativeModules,
  Platform
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { ModelManager, ModelInfo } from './src/services/ModelManager';

export default function App() {
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [downloadProgress, setDownloadProgress] = useState<{ [key: string]: number }>({});
  
  const [isImeEnabled, setIsImeEnabled] = useState(false);
  const [isImeSelected, setIsImeSelected] = useState(false);
  const [useLlmCleaner, setUseLlmCleaner] = useState(false);

  useEffect(() => {
    loadModels();
    checkImeStatus();

    const subscription = AppState.addEventListener('change', (nextAppState: AppStateStatus) => {
      if (nextAppState === 'active') {
        checkImeStatus();
      }
    });

    return () => {
      subscription.remove();
    };
  }, []);

  const loadModels = async () => {
    try {
      const list = await ModelManager.getModels();
      setModels(list);
    } catch (e) {
      console.error('Failed to load models', e);
    } finally {
      setLoading(false);
    }
  };

  const checkImeStatus = async () => {
    if (NativeModules.ModelVerifier) {
      try {
        const enabled = await NativeModules.ModelVerifier.isImeEnabled();
        const selected = await NativeModules.ModelVerifier.isImeSelected();
        const useLlm = await NativeModules.ModelVerifier.getUseLlmCleaner();
        setIsImeEnabled(enabled);
        setIsImeSelected(selected);
        setUseLlmCleaner(useLlm);
      } catch (e) {
        console.error('Failed to check IME status', e);
      }
    }
  };

  const handleOpenSettings = () => {
    if (NativeModules.ModelVerifier) {
      NativeModules.ModelVerifier.openInputMethodSettings();
    }
  };

  const handleSelectKeyboard = () => {
    if (NativeModules.ModelVerifier) {
      NativeModules.ModelVerifier.showInputMethodPicker();
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
    }
  };

  const handleDownload = async (id: string) => {
    setDownloadProgress((prev) => ({ ...prev, [id]: 0 }));
    // Update model status in local state immediately
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
      // Reload to capture final state (checksum result, paths, etc.)
      loadModels();
      checkImeStatus();
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await ModelManager.deleteModel(id);
      loadModels();
      checkImeStatus();
    } catch (e) {
      console.error('Failed to delete model', e);
    }
  };

  const renderModelItem = (info: { item: ModelInfo }) => {
    const item = info.item;
    const progress = downloadProgress[item.id] || 0;
    const progressPercent = Math.round(progress * 100);

    return (
      <View style={styles.card}>
        <View style={styles.cardHeader}>
          <Text style={styles.modelName}>{item.name}</Text>
          <Text style={[styles.statusTag, styles[item.status]]}>
            {item.status.toUpperCase()}
          </Text>
        </View>
        <Text style={styles.filename}>Filename: {item.filename}</Text>
        {item.path ? <Text style={styles.path}>Path: {item.path}</Text> : null}

        {item.status === 'downloading' ? (
          <View style={styles.progressContainer}>
            <View style={styles.progressBarBg}>
              <View style={[styles.progressBarFill, { width: `${progressPercent}%` }]} />
            </View>
            <Text style={styles.progressText}>{progressPercent}%</Text>
          </View>
        ) : null}

        <View style={styles.actions}>
          {item.status === 'pending' || item.status === 'failed' || item.status === 'checksum_failed' ? (
            <TouchableOpacity
              style={styles.downloadButton}
              onPress={() => handleDownload(item.id)}
            >
              <Text style={styles.buttonText}>Download Model</Text>
            </TouchableOpacity>
          ) : null}

          {item.status === 'completed' ? (
            <TouchableOpacity
              style={styles.deleteButton}
              onPress={() => handleDelete(item.id)}
            >
              <Text style={styles.buttonText}>Delete Model</Text>
            </TouchableOpacity>
          ) : null}

          {item.status === 'downloading' ? (
            <ActivityIndicator size="small" color="#007bff" />
          ) : null}
        </View>
      </View>
    );
  };

  const renderHeader = () => {
    const isLlamaDownloaded = models.some(m => m.id === 'cleaner-llama-3b' && m.status === 'completed');

    return (
      <View style={styles.headerContainer}>
        {/* Keyboard Settings Card */}
        <View style={styles.configCard}>
          <Text style={styles.sectionTitle}>Keyboard Status</Text>
          
          <View style={styles.statusRow}>
            <Text style={styles.statusLabel}>Vela Voice Keyboard:</Text>
            <Text style={[styles.statusValue, isImeEnabled ? styles.statusActive : styles.statusInactive]}>
              {isImeEnabled ? 'ENABLED' : 'DISABLED'}
            </Text>
          </View>
          
          <View style={styles.statusRow}>
            <Text style={styles.statusLabel}>Currently Selected:</Text>
            <Text style={[styles.statusValue, isImeSelected ? styles.statusActive : styles.statusInactive]}>
              {isImeSelected ? 'YES' : 'NO'}
            </Text>
          </View>

          <View style={styles.configActions}>
            {!isImeEnabled ? (
              <TouchableOpacity style={styles.primaryButton} onPress={handleOpenSettings}>
                <Text style={styles.buttonText}>1. Enable in Settings</Text>
              </TouchableOpacity>
            ) : !isImeSelected ? (
              <TouchableOpacity style={styles.primaryButton} onPress={handleSelectKeyboard}>
                <Text style={styles.buttonText}>2. Switch Active Keyboard</Text>
              </TouchableOpacity>
            ) : (
              <View style={styles.activeIndicator}>
                <Text style={styles.activeIndicatorText}>✓ Keyboard is Active & Ready</Text>
              </View>
            )}
          </View>
        </View>

        {/* LLM Cleaner Toggle Card */}
        <View style={styles.configCard}>
          <View style={styles.cleanerHeader}>
            <Text style={styles.sectionTitle}>LLM Cleaner Model (Llama 1B)</Text>
            <Switch
              value={useLlmCleaner}
              onValueChange={handleToggleLlm}
              disabled={!isLlamaDownloaded}
              trackColor={{ false: '#767577', true: '#28a745' }}
              thumbColor={useLlmCleaner ? '#fff' : '#f4f3f4'}
            />
          </View>
          
          {!isLlamaDownloaded ? (
            <Text style={styles.disabledText}>
              Download the Llama3.2 1B Cleaner model below to enable advanced offline grammatical and capitalization cleaning.
            </Text>
          ) : (
            <Text style={styles.enabledText}>
              Advanced offline LLM cleaner is enabled. The keyboard will refine your raw transcripts using the Llama model.
            </Text>
          )}
        </View>

        <Text style={styles.modelSectionTitle}>On-Device Model Files</Text>
      </View>
    );
  };

  const renderFooter = () => {
    return (
      <View style={styles.footerContainer}>
        <View style={styles.testCard}>
          <Text style={styles.sectionTitle}>Test Keyboard Input</Text>
          <Text style={styles.testInstruction}>
            Tap the text field below, switch your keyboard to "Vela Voice Input", and tap the microphone icon to transcribe offline!
          </Text>
          <TextInput
            style={styles.textInput}
            placeholder="Tap here to test typing..."
            placeholderTextColor="#888"
            multiline={true}
            numberOfLines={3}
          />
        </View>
      </View>
    );
  };

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#007bff" />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Vela Voice IME Manager</Text>
      <Text style={styles.subtitle}>Manage Whisper & Cleaner models for offline transcription.</Text>

      <FlatList
        data={models}
        keyExtractor={(item) => item.id}
        renderItem={renderModelItem}
        ListHeaderComponent={renderHeader}
        ListFooterComponent={renderFooter}
        contentContainerStyle={styles.list}
      />

      <StatusBar style="auto" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
    paddingTop: 60,
    paddingHorizontal: 20,
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#333',
    textAlign: 'center',
  },
  subtitle: {
    fontSize: 14,
    color: '#666',
    textAlign: 'center',
    marginTop: 5,
    marginBottom: 20,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  list: {
    paddingBottom: 20,
  },
  card: {
    backgroundColor: '#fff',
    borderRadius: 8,
    padding: 16,
    marginBottom: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  modelName: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#333',
  },
  statusTag: {
    fontSize: 10,
    fontWeight: 'bold',
    paddingVertical: 2,
    paddingHorizontal: 6,
    borderRadius: 4,
    overflow: 'hidden',
  },
  pending: {
    backgroundColor: '#ffeeba',
    color: '#856404',
  },
  downloading: {
    backgroundColor: '#b8daff',
    color: '#004085',
  },
  completed: {
    backgroundColor: '#c3e6cb',
    color: '#155724',
  },
  failed: {
    backgroundColor: '#f5c6cb',
    color: '#721c24',
  },
  checksum_failed: {
    backgroundColor: '#f8d7da',
    color: '#721c24',
  },
  filename: {
    fontSize: 12,
    color: '#888',
    marginBottom: 4,
  },
  path: {
    fontSize: 11,
    color: '#555',
    backgroundColor: '#f8f9fa',
    padding: 4,
    borderRadius: 4,
    fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace',
    marginTop: 4,
  },
  progressContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 12,
  },
  progressBarBg: {
    flex: 1,
    height: 8,
    backgroundColor: '#eee',
    borderRadius: 4,
    overflow: 'hidden',
    marginRight: 10,
  },
  progressBarFill: {
    height: '100%',
    backgroundColor: '#007bff',
  },
  progressText: {
    fontSize: 12,
    fontWeight: 'bold',
    color: '#555',
    width: 35,
    textAlign: 'right',
  },
  actions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    marginTop: 12,
  },
  downloadButton: {
    backgroundColor: '#007bff',
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 4,
  },
  deleteButton: {
    backgroundColor: '#dc3545',
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 4,
  },
  buttonText: {
    color: '#fff',
    fontSize: 12,
    fontWeight: 'bold',
  },
  headerContainer: {
    marginBottom: 16,
  },
  footerContainer: {
    marginTop: 16,
    marginBottom: 40,
  },
  configCard: {
    backgroundColor: '#fff',
    borderRadius: 8,
    padding: 16,
    marginBottom: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#333',
    marginBottom: 12,
  },
  modelSectionTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#333',
    marginTop: 8,
    marginBottom: 12,
  },
  statusRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  statusLabel: {
    fontSize: 14,
    color: '#555',
  },
  statusValue: {
    fontSize: 14,
    fontWeight: 'bold',
  },
  statusActive: {
    color: '#28a745',
  },
  statusInactive: {
    color: '#dc3545',
  },
  configActions: {
    marginTop: 12,
    alignItems: 'stretch',
  },
  primaryButton: {
    backgroundColor: '#007bff',
    paddingVertical: 10,
    borderRadius: 6,
    alignItems: 'center',
  },
  activeIndicator: {
    backgroundColor: '#d4edda',
    borderColor: '#c3e6cb',
    borderWidth: 1,
    paddingVertical: 8,
    paddingHorizontal: 12,
    borderRadius: 6,
    alignItems: 'center',
  },
  activeIndicatorText: {
    color: '#155724',
    fontWeight: 'bold',
    fontSize: 14,
  },
  cleanerHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  disabledText: {
    fontSize: 12,
    color: '#666',
    lineHeight: 18,
  },
  enabledText: {
    fontSize: 12,
    color: '#28a745',
    lineHeight: 18,
  },
  testCard: {
    backgroundColor: '#fff',
    borderRadius: 8,
    padding: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  testInstruction: {
    fontSize: 12,
    color: '#666',
    marginBottom: 12,
    lineHeight: 18,
  },
  textInput: {
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 6,
    padding: 10,
    fontSize: 14,
    color: '#333',
    backgroundColor: '#f9f9f9',
    textAlignVertical: 'top',
    height: 80,
  },
});
