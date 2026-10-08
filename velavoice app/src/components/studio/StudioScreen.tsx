/**
 * Module: src/components/studio/StudioScreen
 * Intent: Studio tab — transcript review/editing, Scribe LLM rewrite sim, Dictionary sandbox.
 * Responsibilities: Render review tooling; all state lives in App (props-only screen).
 * Public API: StudioScreen + StudioScreenProps
 * Invariants: Pure presentation — saves, rewrites, and cleanup are prop callbacks.
 * Side Effects: None locally (TextInput controlled by parent state).
 */
import React from 'react';
import {
  ActivityIndicator,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { Recording } from '../RecordingCard';
import { TranscriptionEditor } from '../TranscriptionEditor';
import { sharedScreenStyles } from '../../theme/appStyles';

export interface StudioScreenProps {
  recordings: Recording[];
  selectedRecordingId: string;
  studioSegment: 'cleaned' | 'raw';
  isEditingTranscript: boolean;
  scribeStyle: string;
  scribeInstruction: string;
  scribeDrafts: string[];
  selectedScribeDraftIndex: number;
  isGeneratingScribe: boolean;
  scribeAppName: string;
  scribeInputType: string;
  showPromptDebug: boolean;
  testText: string;
  testCleanedText: string;
  setStudioSegment: (value: 'cleaned' | 'raw') => void;
  setIsEditingTranscript: (value: boolean) => void;
  setEditingTextValue: (value: string) => void;
  setScribeStyle: (value: string) => void;
  setScribeInstruction: (value: string) => void;
  setScribeDrafts: (value: string[]) => void;
  setSelectedScribeDraftIndex: (value: number) => void;
  setScribeAppName: (value: string) => void;
  setScribeInputType: (value: string) => void;
  setShowPromptDebug: (value: boolean) => void;
  setTestText: (value: string) => void;
  runScribeRewrite: () => void;
  commitScribeDraft: () => void;
  runCustomClean: () => void;
  handleSaveCorrection: (
    audioId: string,
    original: string,
    corrected: string,
    edits: any[],
    editDistance: number,
    scribeStyle?: string,
    quarantined?: boolean
  ) => Promise<void>;
}

export const StudioScreen: React.FC<StudioScreenProps> = ({
  recordings,
  selectedRecordingId,
  studioSegment,
  isEditingTranscript,
  scribeStyle,
  scribeInstruction,
  scribeDrafts,
  selectedScribeDraftIndex,
  isGeneratingScribe,
  scribeAppName,
  scribeInputType,
  showPromptDebug,
  testText,
  testCleanedText,
  setStudioSegment,
  setIsEditingTranscript,
  setEditingTextValue,
  setScribeStyle,
  setScribeInstruction,
  setScribeDrafts,
  setSelectedScribeDraftIndex,
  setScribeAppName,
  setScribeInputType,
  setShowPromptDebug,
  setTestText,
  runScribeRewrite,
  commitScribeDraft,
  runCustomClean,
  handleSaveCorrection,
}) => {
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

// Screen-local styles; tab-level primitives come from the shared theme module.
const styles = {
  ...sharedScreenStyles,
  ...StyleSheet.create({
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
    studioScribeCard: {
      backgroundColor: '#161d1c',
      borderRadius: 12,
      padding: 16,
      marginBottom: 16,
      borderWidth: 1,
      borderColor: '#3c4948',
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
    scribeResultsContainer: {
      marginTop: 14,
    },
    sandboxResultTitle: {
      fontSize: 12,
      fontWeight: 'bold',
      color: '#ddb7ff',
      marginBottom: 4,
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
  }),
};
