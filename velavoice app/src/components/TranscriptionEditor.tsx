import React, { useState, useEffect } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet } from 'react-native';
import { calculateEditDistance, getEdits } from '../utils/editCalculator';

export interface TranscriptionEditorProps {
  audioId: string;
  originalTranscription: string;
  onSave: (
    audioId: string,
    original: string,
    corrected: string,
    edits: any[],
    editDistance: number,
    scribeStyle?: string
  ) => void;
  onCancel: () => void;
}

const SCRIBE_STYLES = ['Professional', 'Casual', 'Bullet Points', 'Email Draft', 'Proofread', 'Custom'];

export const TranscriptionEditor: React.FC<TranscriptionEditorProps> = ({
  audioId,
  originalTranscription,
  onSave,
  onCancel,
}) => {
  const [correctedText, setCorrectedText] = useState(originalTranscription);
  const [selectedStyle, setSelectedStyle] = useState('Professional');
  const [isPlayingAudio, setIsPlayingAudio] = useState(false);
  const [isReCleaning, setIsReCleaning] = useState(false);

  useEffect(() => {
    setCorrectedText(originalTranscription);
  }, [originalTranscription]);

  const handleTogglePlayback = () => {
    setIsPlayingAudio(!isPlayingAudio);
  };

  const handleReCleanTranscript = () => {
    setIsReCleaning(true);
    // Simulate Scribe LLM style re-cleaning
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

  const handleSave = () => {
    const edits = getEdits(originalTranscription, correctedText);
    const editDistance = calculateEditDistance(originalTranscription, correctedText);
    onSave(audioId, originalTranscription, correctedText, edits, editDistance, selectedStyle);
  };

  return (
    <View style={styles.container}>
      {/* Audio Playback Waveform Bar */}
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

      {/* Original Transcription */}
      <Text style={styles.sectionTitle}>Original Transcription</Text>
      <View style={styles.readOnlyContainer}>
        <Text style={styles.readOnlyText}>{originalTranscription}</Text>
      </View>

      {/* Scribe Style Switcher */}
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

      <TouchableOpacity style={styles.recleanButton} onPress={handleReCleanTranscript} disabled={isReCleaning}>
        <Text style={styles.recleanButtonText}>
          {isReCleaning ? 'Re-Cleaning with Scribe...' : `Re-Clean with ${selectedStyle} Style`}
        </Text>
      </TouchableOpacity>

      {/* Corrected Transcription */}
      <Text style={styles.sectionTitle}>Final Transcription</Text>
      <TextInput
        style={styles.textInput}
        value={correctedText}
        onChangeText={setCorrectedText}
        multiline
        numberOfLines={4}
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
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    backgroundColor: '#111716',
    borderWidth: 1,
    borderColor: '#3c4948',
    borderRadius: 8,
    padding: 16,
    marginVertical: 12,
  },
  sectionTitle: {
    color: '#859491',
    fontSize: 12,
    fontWeight: 'bold',
    textTransform: 'uppercase',
    marginBottom: 6,
    letterSpacing: 0.5,
  },
  waveformContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#0a0d0d',
    padding: 10,
    borderRadius: 6,
    marginBottom: 12,
  },
  playButton: {
    backgroundColor: '#00d6aa',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 4,
  },
  playButtonText: {
    color: '#091515',
    fontWeight: 'bold',
    fontSize: 12,
  },
  waveformBarContainer: {
    marginLeft: 12,
    flex: 1,
  },
  waveformText: {
    color: '#88f3dd',
    fontFamily: 'monospace',
    fontSize: 12,
  },
  readOnlyContainer: {
    backgroundColor: '#0a0d0d',
    borderColor: '#212928',
    borderWidth: 1,
    borderRadius: 6,
    padding: 10,
    marginBottom: 12,
  },
  readOnlyText: {
    color: '#a7b5b2',
    fontSize: 14,
  },
  stylePickerRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
    marginBottom: 10,
  },
  styleChip: {
    backgroundColor: '#1c2624',
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 14,
  },
  activeStyleChip: {
    backgroundColor: '#00d6aa',
  },
  styleChipText: {
    color: '#a7b5b2',
    fontSize: 12,
  },
  activeStyleChipText: {
    color: '#091515',
    fontWeight: 'bold',
  },
  recleanButton: {
    backgroundColor: '#1b3834',
    borderColor: '#00d6aa',
    borderWidth: 1,
    padding: 10,
    borderRadius: 6,
    alignItems: 'center',
    marginBottom: 14,
  },
  recleanButtonText: {
    color: '#00d6aa',
    fontWeight: 'bold',
    fontSize: 13,
  },
  textInput: {
    backgroundColor: '#050707',
    borderColor: '#293533',
    borderWidth: 1,
    borderRadius: 6,
    color: '#ffffff',
    fontSize: 14,
    padding: 10,
    minHeight: 80,
    marginBottom: 14,
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 8,
  },
  button: {
    paddingVertical: 8,
    paddingHorizontal: 16,
    borderRadius: 4,
  },
  cancelButton: {
    backgroundColor: '#1c2624',
  },
  saveButton: {
    backgroundColor: '#00d6aa',
  },
  buttonText: {
    color: '#a7b5b2',
    fontSize: 14,
    fontWeight: 'bold',
  },
  saveButtonText: {
    color: '#091515',
  },
});
