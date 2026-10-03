import React, { useState, useEffect } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet } from 'react-native';
import { calculateEditDistance, getEdits } from '../utils/editCalculator';
import { isQuarantinedEntry, quarantineBadgeText } from '../utils/quarantineBadge';
export const TranscriptionEditor = ({ audioId, originalTranscription, onSave, onCancel, quarantined, }) => {
    const [correctedText, setCorrectedText] = useState(originalTranscription);
    useEffect(() => {
        setCorrectedText(originalTranscription);
    }, [originalTranscription]);
    const handleSave = () => {
        const edits = getEdits(originalTranscription, correctedText);
        const editDistance = calculateEditDistance(originalTranscription, correctedText);
        onSave(audioId, originalTranscription, correctedText, edits, editDistance, undefined, quarantined === true);
    };
    return (<View style={styles.container}>
      {isQuarantinedEntry({ quarantined }) && (
        <View style={styles.quarantineBanner}>
          <Text style={styles.quarantineBannerText}>🔒 {quarantineBadgeText()} — edits stay on this device</Text>
        </View>
      )}
      <Text style={styles.sectionTitle}>Original Transcription</Text>
      <View style={styles.readOnlyContainer}>
        <Text style={styles.readOnlyText}>{originalTranscription}</Text>
      </View>

      <Text style={styles.sectionTitle}>Corrected Transcription</Text>
      <TextInput style={styles.textInput} value={correctedText} onChangeText={setCorrectedText} multiline numberOfLines={4} textAlignVertical="top" placeholder="Edit transcription here..." placeholderTextColor="#5a6f6d"/>

      <View style={styles.buttonRow}>
        <TouchableOpacity style={[styles.button, styles.cancelButton]} onPress={onCancel}>
          <Text style={styles.buttonText}>Cancel</Text>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.button, styles.saveButton]} onPress={handleSave}>
          <Text style={[styles.buttonText, styles.saveButtonText]}>Save Correction</Text>
        </TouchableOpacity>
      </View>
    </View>);
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
    quarantineBanner: {
        backgroundColor: '#5c1a1a',
        borderColor: '#ff6b6b',
        borderWidth: 1,
        borderRadius: 6,
        padding: 10,
        marginBottom: 8,
    },
    quarantineBannerText: {
        color: '#ffffff',
        fontSize: 12,
        fontWeight: 'bold',
    },
    sectionTitle: {
        color: '#859491',
        fontSize: 12,
        fontWeight: 'bold',
        textTransform: 'uppercase',
        marginBottom: 6,
        letterSpacing: 0.5,
    },
    readOnlyContainer: {
        backgroundColor: '#0a0d0d',
        borderColor: '#212928',
        borderWidth: 1,
        borderRadius: 6,
        padding: 10,
        marginBottom: 14,
    },
    readOnlyText: {
        color: '#859491',
        fontSize: 14,
        lineHeight: 20,
    },
    textInput: {
        backgroundColor: '#161d1c',
        borderColor: '#3c4948',
        borderWidth: 1,
        borderRadius: 6,
        padding: 10,
        color: '#dde4e2',
        fontSize: 14,
        lineHeight: 20,
        minHeight: 100,
        marginBottom: 16,
    },
    buttonRow: {
        flexDirection: 'row',
        justifyContent: 'flex-end',
        gap: 10,
    },
    button: {
        paddingVertical: 8,
        paddingHorizontal: 16,
        borderRadius: 6,
        alignItems: 'center',
        justifyContent: 'center',
    },
    cancelButton: {
        backgroundColor: '#dc3545',
    },
    saveButton: {
        backgroundColor: '#62f9ee',
    },
    buttonText: {
        fontSize: 13,
        fontWeight: 'bold',
        color: '#ffffff',
    },
    saveButtonText: {
        color: '#003734',
    },
});
