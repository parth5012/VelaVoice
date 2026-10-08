/**
 * Module: src/hooks/useSyncToDrive
 * Intent: Google Drive sync state — credentials bootstrap, status refresh, sync action.
 * Responsibilities: Own driveConfigured/isSyncing/syncResult/counts/recent list and the
 *   native GoogleDriveSync calls behind them.
 * Public API: useSyncToDrive() -> sync state values + initDriveCredentials/refreshDriveStatus/handleSyncToDrive.
 * Invariants: No-ops when the native module is absent (web/tests); error messages preserved.
 * Side Effects: NativeModules.GoogleDriveSync; Constants.extra for first-run credential import.
 */
import { useState } from 'react';
import { NativeModules } from 'react-native';
import Constants from 'expo-constants';

export function useSyncToDrive() {
  const [driveConfigured, setDriveConfigured] = useState(false);
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncResult, setSyncResult] = useState<string | null>(null);
  const [unsyncedCount, setUnsyncedCount] = useState(0);
  const [totalTranscriptions, setTotalTranscriptions] = useState(0);
  const [recentTranscriptions, setRecentTranscriptions] = useState<any[]>([]);

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

  return {
    driveConfigured,
    isSyncing,
    syncResult,
    unsyncedCount,
    totalTranscriptions,
    recentTranscriptions,
    initDriveCredentials,
    refreshDriveStatus,
    handleSyncToDrive,
  };
}
