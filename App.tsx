import React, { useEffect, useState } from 'react';
import { StyleSheet, Text, View, FlatList, ActivityIndicator, TouchableOpacity } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { ModelManager, ModelInfo } from './src/services/ModelManager';

export default function App() {
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [downloadProgress, setDownloadProgress] = useState<{ [key: string]: number }>({});

  useEffect(() => {
    loadModels();
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

  const handleDownload = async (id: string) => {
    setDownloadProgress((prev) => ({ ...prev, [id]: 0 }));
    // Update model status in local UI state immediately
    setModels((prevModels) =>
      prevModels.map((m) => (m.id === id ? { ...m, status: 'downloading' } : m))
    );

    try {
      await ModelManager.downloadModel(id, (progress) => {
        setDownloadProgress((prev) => ({ ...prev, [id]: progress }));
      });
    } catch (e) {
      console.error('Download failed', e);
    } finally {
      // Reload from DB to capture final state (checksum result, paths, etc.)
      loadModels();
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await ModelManager.deleteModel(id);
      loadModels();
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
        {item.path && <Text style={styles.path}>Path: {item.path}</Text>}

        {item.status === 'downloading' && (
          <View style={styles.progressContainer}>
            <View style={styles.progressBarBg}>
              <View style={[styles.progressBarFill, { width: `${progressPercent}%` }]} />
            </View>
            <Text style={styles.progressText}>{progressPercent}%</Text>
          </View>
        )}

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
    fontFamily: 'monospace',
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
});
