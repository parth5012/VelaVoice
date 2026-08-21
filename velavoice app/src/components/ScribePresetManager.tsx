import React, { useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet, ScrollView } from 'react-native';

export interface ScribePreset {
  id: string;
  title: string;
  icon: string;
  systemPrompt: string;
  temperature: number;
}

export interface ScribePresetManagerProps {
  initialPresets?: ScribePreset[];
  onSavePresets?: (presets: ScribePreset[]) => void;
}

const DEFAULT_PRESETS: ScribePreset[] = [
  {
    id: 'code-commenter',
    title: 'Code Commenter',
    icon: 'code',
    systemPrompt: 'Convert raw input into concise JSDoc / inline code comments for {app_name}. Context: {surrounding_text}',
    temperature: 0.2,
  },
  {
    id: 'slack-digest',
    title: 'Slack Digest',
    icon: 'chat',
    systemPrompt: 'Summarize dictation into bullet points suitable for Slack messages in {app_name}.',
    temperature: 0.4,
  },
];

export const ScribePresetManager: React.FC<ScribePresetManagerProps> = ({
  initialPresets = DEFAULT_PRESETS,
  onSavePresets,
}) => {
  const [presets, setPresets] = useState<ScribePreset[]>(initialPresets);
  const [activePresetId, setActivePresetId] = useState<string>(initialPresets[0]?.id || '');
  const [sampleRawText, setSampleRawText] = useState('um yeah so we need to add a new API endpoint for user profile edits');
  const [previewOutput, setPreviewOutput] = useState('');

  const activePreset = presets.find((p) => p.id === activePresetId) || presets[0];

  const updateActivePreset = (field: keyof ScribePreset, value: any) => {
    setPresets((prev) =>
      prev.map((p) => (p.id === activePresetId ? { ...p, [field]: value } : p))
    );
  };

  const handleAddNewPreset = () => {
    const newId = `custom-${Date.now()}`;
    const newPreset: ScribePreset = {
      id: newId,
      title: 'New Custom Scribe Style',
      icon: 'sparkles',
      systemPrompt: 'Rewrite input for {app_name} with context: {surrounding_text}',
      temperature: 0.3,
    };
    setPresets((prev) => [...prev, newPreset]);
    setActivePresetId(newId);
  };

  const handleTestPrompt = () => {
    if (!activePreset) return;
    let compiled = activePreset.systemPrompt
      .replace(/{app_name}|{{app_name}}/g, 'Slack')
      .replace(/{target_field_type}|{{target_field_type}}/g, 'chat')
      .replace(/{surrounding_text}|{{surrounding_text}}/g, 'Project Discussion Channel');

    setPreviewOutput(`[Sandbox Test Result (${activePreset.title})]\nSystem Prompt:\n${compiled}\n\nProcessed Input: "${sampleRawText}"`);
  };

  const handleSave = () => {
    if (onSavePresets) {
      onSavePresets(presets);
    }
  };

  return (
    <ScrollView style={styles.container}>
      <Text style={styles.headerTitle}>Scribe Presets Manager (Engine Room)</Text>
      
      {/* Preset Cards Selector */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.cardContainer}>
        {presets.map((preset) => (
          <TouchableOpacity
            key={preset.id}
            style={[styles.presetCard, activePresetId === preset.id && styles.activeCard]}
            onPress={() => setActivePresetId(preset.id)}
          >
            <Text style={[styles.cardTitle, activePresetId === preset.id && styles.activeCardText]}>
              {preset.title}
            </Text>
          </TouchableOpacity>
        ))}
        <TouchableOpacity style={styles.addCardButton} onPress={handleAddNewPreset}>
          <Text style={styles.addCardText}>+ Add Preset</Text>
        </TouchableOpacity>
      </ScrollView>

      {/* Editor Form */}
      {activePreset && (
        <View style={styles.formContainer}>
          <Text style={styles.label}>Preset Title</Text>
          <TextInput
            style={styles.input}
            value={activePreset.title}
            onChangeText={(val) => updateActivePreset('title', val)}
          />

          <Text style={styles.label}>System Prompt Template</Text>
          <Text style={styles.subtext}>Variables: {"{app_name}, {target_field_type}, {surrounding_text}"}</Text>
          <TextInput
            style={[styles.input, styles.multilineInput]}
            value={activePreset.systemPrompt}
            onChangeText={(val) => updateActivePreset('systemPrompt', val)}
            multiline
            numberOfLines={4}
          />

          {/* Sandbox Test */}
          <Text style={styles.sectionTitle}>Prompt Sandbox Test</Text>
          <TextInput
            style={styles.input}
            value={sampleRawText}
            onChangeText={setSampleRawText}
            placeholder="Enter sample raw voice text..."
            placeholderTextColor="#5a6f6d"
          />

          <TouchableOpacity style={styles.testButton} onPress={handleTestPrompt}>
            <Text style={styles.testButtonText}>Test Preset Prompt</Text>
          </TouchableOpacity>

          {previewOutput ? (
            <View style={styles.previewContainer}>
              <Text style={styles.previewText}>{previewOutput}</Text>
            </View>
          ) : null}

          <TouchableOpacity style={styles.saveButton} onPress={handleSave}>
            <Text style={styles.saveButtonText}>Save Presets & Sync IME</Text>
          </TouchableOpacity>
        </View>
      )}
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#091515', padding: 16 },
  headerTitle: { fontSize: 20, fontWeight: 'bold', color: '#88f3dd', marginBottom: 16 },
  cardContainer: { flexDirection: 'row', marginBottom: 20 },
  presetCard: { backgroundColor: '#132826', padding: 14, borderRadius: 10, marginRight: 10, borderBottomWidth: 3, borderBottomColor: 'transparent' },
  activeCard: { backgroundColor: '#1d3e3b', borderBottomColor: '#00d6aa' },
  cardTitle: { color: '#8fa8a5', fontWeight: '600' },
  activeCardText: { color: '#ffffff', fontWeight: 'bold' },
  addCardButton: { backgroundColor: '#00d6aa22', padding: 14, borderRadius: 10, borderStyle: 'dashed', borderWidth: 1, borderColor: '#00d6aa' },
  addCardText: { color: '#00d6aa', fontWeight: '600' },
  formContainer: { backgroundColor: '#0e201e', padding: 16, borderRadius: 12 },
  label: { color: '#88f3dd', fontSize: 14, fontWeight: '600', marginBottom: 4 },
  subtext: { color: '#5a6f6d', fontSize: 12, marginBottom: 8 },
  input: { backgroundColor: '#132826', color: '#ffffff', padding: 12, borderRadius: 8, fontSize: 14, marginBottom: 16 },
  multilineInput: { minHeight: 80, textAlignVertical: 'top' },
  sectionTitle: { fontSize: 16, fontWeight: 'bold', color: '#88f3dd', marginTop: 10, marginBottom: 8 },
  testButton: { backgroundColor: '#00d6aa', padding: 12, borderRadius: 8, alignItems: 'center', marginBottom: 12 },
  testButtonText: { color: '#091515', fontWeight: 'bold', fontSize: 14 },
  previewContainer: { backgroundColor: '#071010', padding: 12, borderRadius: 8, marginBottom: 16 },
  previewText: { color: '#88f3dd', fontSize: 13, fontFamily: 'monospace' },
  saveButton: { backgroundColor: '#88f3dd', padding: 14, borderRadius: 8, alignItems: 'center', marginTop: 8 },
  saveButtonText: { color: '#091515', fontWeight: 'bold', fontSize: 15 },
});
