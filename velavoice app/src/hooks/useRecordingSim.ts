/**
 * Module: src/hooks/useRecordingSim
 * Intent: Simulated recording session state — capture timer, amplitude wave, start/stop.
 * Responsibilities: Own isRecording/recordingSeconds/recordingAmplitudes, the interval
 *   effects, permission-gated start, and stop-time Recording construction.
 * Public API: useRecordingSim(options) -> { isRecording, recordingSeconds,
 *   recordingAmplitudes, startRecordingSim, stopRecordingSim }.
 * Invariants: Pure simulation (no mic capture); library/navigation effects stay in the
 *   parent via onRecordingFinished.
 * Side Effects: alert() when mic permission is denied; timers while recording.
 */
import { useEffect, useState } from 'react';
import { Recording } from '../components/RecordingCard';

export interface UseRecordingSimOptions {
  requestMicPermission: () => Promise<boolean>;
  recordings: Recording[];
  onRecordingFinished: (rec: Recording, runCleaner: boolean) => void;
}

export function useRecordingSim({
  requestMicPermission,
  recordings,
  onRecordingFinished,
}: UseRecordingSimOptions) {
  const [isRecording, setIsRecording] = useState(false);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const [recordingAmplitudes, setRecordingAmplitudes] = useState<number[]>([]);

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

  // Start simulated recording
  const startRecordingSim = async () => {
    const hasPermission = await requestMicPermission();
    if (!hasPermission) {
      alert('Microphone permission required to record.');
      return;
    }
    setIsRecording(true);
  };

  // Stop simulated recording and hand the finished Recording to the parent
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

    onRecordingFinished(newRec, runCleaner);
  };

  return {
    isRecording,
    recordingSeconds,
    recordingAmplitudes,
    startRecordingSim,
    stopRecordingSim,
  };
}
