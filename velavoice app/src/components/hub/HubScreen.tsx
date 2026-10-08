/**
 * Module: src/components/hub/HubScreen
 * Intent: Voice Hub tab — recording library list, Whisper readiness badge, record FAB.
 * Responsibilities: Render the library; all state lives in App (props-only screen).
 * Public API: HubScreen + HubScreenProps
 * Invariants: Pure presentation — recording selection and capture are prop callbacks.
 * Side Effects: None locally.
 */
import React from 'react';
import { FlatList, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { RecordingCard, Recording } from '../RecordingCard';
import { ModelInfo } from '../../services/ModelManager';
import { sharedScreenStyles } from '../../theme/appStyles';

export interface HubScreenProps {
  models: ModelInfo[];
  recordings: Recording[];
  selectedRecordingId: string;
  /** Selects the recording AND navigates to the Studio (parent wires both). */
  onSelectRecording: (id: string) => void;
  onStartRecording: () => void | Promise<void>;
}

export const HubScreen: React.FC<HubScreenProps> = ({
  models,
  recordings,
  selectedRecordingId,
  onSelectRecording,
  onStartRecording,
}) => {
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
                  onSelectRecording(item.id);
                }}
              />
            );
          }}
        />

        {/* Large Floating Record FAB */}
        <TouchableOpacity style={styles.recordFab} onPress={onStartRecording}>
          <Text style={styles.recordFabIcon}>🎤</Text>
        </TouchableOpacity>
      </View>
    );
};

// Screen-local styles; tab-level primitives come from the shared theme module.
const styles = {
  ...sharedScreenStyles,
  ...StyleSheet.create({
    hubHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: 20,
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
  }),
};
