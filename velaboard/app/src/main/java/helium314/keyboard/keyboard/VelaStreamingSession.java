/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import com.velavoice.sdk.RevisionMarker;
import com.velavoice.sdk.StreamingPipeline;
import com.velavoice.sdk.StreamingTranscriptionCallback;
import com.velavoice.sdk.VelaException;
import com.velavoice.sdk.cleaner.CleanerConfig;
import com.velavoice.sdk.cleaner.TextCleaner;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.RichInputConnection;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.settings.TranscriptionStorage;

/**
 * Live streaming transcription session (PREF_VELA_STREAMING_MODE = "streamed").
 *
 * Owns a {@link StreamingPipeline} and drives the editor through a composing-span state machine:
 * partial revisions extend the current composing span, commit revisions finalize it and open a
 * fresh one, and onFinal finalizes everything. The SDK's RevisionMarker.range is unreliable, so
 * only marker.text is used and all offsets are tracked here.
 *
 * StreamingPipeline bypasses the SDK's TextCleaner, so the cleanup parity that the instant path
 * gets for free (fillers, personal dictionary, Scribe, LLM) is applied here on the final pass:
 * the streamed text is cleaned off the main thread and then swapped into the editor.
 *
 * All SDK callbacks arrive on background threads and are posted to LatinIME.mHandler before any
 * editor or UI work happens.
 */
public final class VelaStreamingSession implements StreamingTranscriptionCallback {
    private static final String TAG = VelaStreamingSession.class.getSimpleName();
    private static final long FINALIZE_TIMEOUT_MS = 15000;
    private static final long CLEANUP_TIMEOUT_MS = 10000;
    private static final long TYPING_INTERVAL_MS = 35;

    private static final int SWAP_COMPOSING = 0;
    private static final int SWAP_COMMITTED = 1;
    private static final int SWAP_FALLBACK = 2;

    public interface Listener {
        void onStreamingAmplitude(float normalized);
        void onStreamingFinished(boolean cancelled);
        void onStreamingError(String message);
    }

    /**
     * Snapshot of every cleanup input the instant path resolves before recording starts: the
     * pref-derived {@link CleanerConfig} plus the editor context that path passes as ScribeInput.
     */
    public static final class CleanupSpec {
        final CleanerConfig config;
        final String contextBefore;
        final String contextAfter;
        final String appName;
        final String inputType;
        final String overrideStyle;

        public CleanupSpec(final CleanerConfig config, final String contextBefore,
                final String contextAfter, final String appName, final String inputType,
                final String overrideStyle) {
            this.config = config;
            this.contextBefore = contextBefore;
            this.contextAfter = contextAfter;
            this.appName = appName;
            this.inputType = inputType;
            this.overrideStyle = overrideStyle;
        }
    }

    private final LatinIME mLatinIME;
    private final StreamingPipeline mPipeline;
    private final String mPipelineMode;
    private final boolean mPrivacySensitive;
    private final Listener mListener;
    private final CleanupSpec mCleanupSpec;
    private final FutureTask<TextCleaner> mCleanerTask;

    private final StringBuilder mCommitted = new StringBuilder();
    private final StringBuilder mComposing = new StringBuilder();

    private boolean mBatchFallback = false;
    private int mFallbackTextInEditor = 0;
    private boolean mStopRequested = false;
    private boolean mFinalizing = false;
    private boolean mFinalizeCompleted = false;
    private boolean mFinished = false;
    private boolean mCancelled = false;
    private boolean mReleased = false;
    private long mStartTimeMs = 0;

    private final StringBuilder mRevealed = new StringBuilder();
    private final StringBuilder mTypingQueue = new StringBuilder();
    private final Runnable mTypingAnimator = new Runnable() {
        @Override
        public void run() {
            if (mFinished || mBatchFallback || mTypingQueue.length() == 0) return;
            final char c = mTypingQueue.charAt(0);
            mTypingQueue.deleteCharAt(0);
            mRevealed.append(c);
            final RichInputConnection connection = mLatinIME.getRichInputConnection();
            if (connection != null) {
                connection.beginBatchEdit();
                final boolean applied = connection.setComposingText(mRevealed.toString(), 1);
                connection.endBatchEdit();
                if (!applied) {
                    enterBatchFallback();
                    return;
                }
            }
            if (mTypingQueue.length() > 0) {
                mLatinIME.mHandler.postDelayed(this, TYPING_INTERVAL_MS);
            }
        }
    };

    private final Runnable mFinalizeTimeout = new Runnable() {
        @Override
        public void run() {
            if (mFinished || mFinalizing) return;
            Log.w(TAG, "no onFinal within timeout, finalizing locally");
            finalizeSession();
        }
    };

    private String mPendingRaw = null;
    private int mPendingSwapMode = SWAP_COMMITTED;

    private final Runnable mCleanupTimeout = new Runnable() {
        @Override
        public void run() {
            if (mFinalizeCompleted) return;
            Log.w(TAG, "cleanup watchdog fired, finalizing with raw streamed text");
            completeFinalize(mPendingSwapMode, mPendingRaw == null ? "" : mPendingRaw, null);
        }
    };

    public VelaStreamingSession(final LatinIME latinIME, final StreamingPipeline pipeline,
            final String pipelineMode, final boolean privacySensitive,
            final CleanupSpec cleanupSpec, final Listener listener) {
        mLatinIME = latinIME;
        mPipeline = pipeline;
        mPipelineMode = pipelineMode;
        mPrivacySensitive = privacySensitive;
        mCleanupSpec = cleanupSpec;
        mListener = listener;
        mCleanerTask = cleanupSpec == null ? null
            : new FutureTask<>(() -> TextCleaner.getOrCreate(cleanupSpec.config));
        mPipeline.setCallback(this);
    }

    /** Blocking; must be called off the main thread. */
    public void start() {
        mStartTimeMs = System.currentTimeMillis();
        warmUpCleaner();
        mPipeline.start(mPipelineMode);
        Log.i(TAG, "streaming started, mode=" + mPipelineMode);
    }

    /**
     * The TextCleaner constructor loads the ONNX cleanup model, which takes seconds. Build it in
     * parallel with the recording so the final pass usually finds it already warm.
     */
    private void warmUpCleaner() {
        if (mCleanerTask == null) return;
        new Thread(mCleanerTask, "vela-cleaner-warmup").start();
    }

    public boolean isActive() {
        return !mFinished;
    }

    /** User confirmed: finalize the stream and wait for the trailing onFinal. */
    public void stop() {
        if (mFinished || mStopRequested) return;
        mStopRequested = true;
        mLatinIME.mHandler.postDelayed(mFinalizeTimeout, FINALIZE_TIMEOUT_MS);
        stopPipelineAsync();
    }

    /** User cancelled: drop the in-flight composing span and tear the pipeline down. */
    public void cancel() {
        if (mFinished || mStopRequested) return;
        mCancelled = true;
        mStopRequested = true;
        mLatinIME.mHandler.post(() -> {
            clearComposingRegion();
            markFinished(true);
        });
        stopPipelineAsync();
    }

    /** Key tap or input teardown mid-stream: keep what is already there, stop streaming. */
    public void abortKeepingText() {
        if (mFinished || mFinalizing) return;
        mStopRequested = true;
        mLatinIME.mHandler.post(() -> {
            finishSegment();
            markFinished(false);
        });
        stopPipelineAsync();
    }

    public void release() {
        if (mReleased) return;
        mReleased = true;
        mFinished = true;
        mLatinIME.mHandler.removeCallbacks(mFinalizeTimeout);
        mLatinIME.mHandler.removeCallbacks(mCleanupTimeout);
        mLatinIME.mHandler.removeCallbacks(mTypingAnimator);
        mTypingQueue.setLength(0);
        if (mCleanerTask != null) {
            mCleanerTask.cancel(true);
        }
        new Thread(() -> {
            try {
                mPipeline.stop();
            } catch (Exception e) {
                Log.e(TAG, "pipeline stop failed", e);
            }
            try {
                mPipeline.release();
            } catch (Exception e) {
                Log.e(TAG, "pipeline release failed", e);
            }
        }).start();
    }

    private void stopPipelineAsync() {
        new Thread(() -> {
            try {
                mPipeline.stop();
            } catch (Exception e) {
                Log.e(TAG, "pipeline stop failed", e);
            }
        }).start();
    }

    @Override
    public void onAmplitude(final float normalized) {
        mLatinIME.mHandler.post(() -> {
            if (mFinished) return;
            mListener.onStreamingAmplitude(normalized);
        });
    }

    @Override
    public void onRevisionMarker(final RevisionMarker marker) {
        final String type = marker.getType();
        final String text = marker.getText();
        mLatinIME.mHandler.post(() -> {
            if (mFinished || mCancelled) return;
            if ("partial".equals(type)) {
                Log.d(TAG, "partial revision, +" + (text == null ? 0 : text.length()) + " chars");
                appendDelta(text);
                queueTyping(mComposing.length() - text.length());
            } else if ("commit".equals(type)) {
                Log.d(TAG, "commit revision, " + (text == null ? 0 : text.length()) + " chars");
                flushTyping();
                if (mComposing.length() == 0 && text != null && !text.isEmpty()
                        && !alreadyEndsWith(text)) {
                    appendDelta(text);
                    applyComposing();
                }
                finishSegment();
            }
        });
    }

    @Override
    public void onFinal(final String text) {
        mLatinIME.mHandler.post(() -> {
            if (mFinished || mFinalizing) return;
            Log.d(TAG, "final, " + (text == null ? 0 : text.length()) + " chars");
            if (fullTextLength() == 0 && text != null && !text.isEmpty()) {
                appendDelta(text);
                queueTyping(0);
                flushTyping();
            }
            finalizeSession();
        });
    }

    @Override
    public void onError(final VelaException error) {
        final String message = error.getMessage();
        mLatinIME.mHandler.post(() -> {
            if (mFinished || mFinalizing) return;
            finishSegment();
            markFinished(false);
            mListener.onStreamingError(message);
        });
    }

    private void finalizeSession() {
        if (mFinished || mFinalizing) return;
        mFinalizing = true;
        mLatinIME.mHandler.removeCallbacks(mFinalizeTimeout);
        flushTyping();

        final int swapMode;
        if (mBatchFallback) {
            finishSegment();
            swapMode = SWAP_FALLBACK;
        } else if (mCommitted.length() == 0 && mComposing.length() > 0) {
            swapMode = SWAP_COMPOSING;
        } else {
            finishSegment();
            swapMode = SWAP_COMMITTED;
        }
        final String raw = swapMode == SWAP_COMPOSING
            ? mComposing.toString() : mCommitted.toString();

        if (mCleanupSpec == null || raw.trim().isEmpty()) {
            completeFinalize(swapMode, raw, null);
            return;
        }
        mPendingRaw = raw;
        mPendingSwapMode = swapMode;
        mLatinIME.mHandler.postDelayed(mCleanupTimeout, CLEANUP_TIMEOUT_MS + 2000);
        new Thread(() -> {
            final String cleaned = runCleanup(raw);
            mLatinIME.mHandler.post(() -> completeFinalize(swapMode, raw, cleaned));
        }, "vela-cleanup").start();
    }

    /**
     * Runs the SDK cleaner off the main thread under a hard timeout. Returns null when cleanup
     * could not be produced in time, in which case the raw streamed text is kept as-is.
     */
    private String runCleanup(final String raw) {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<String> future = executor.submit(() -> {
                final TextCleaner cleaner = mCleanerTask.get();
                return cleaner.clean(raw, mCleanupSpec.contextBefore, mCleanupSpec.contextAfter,
                    mCleanupSpec.appName, mCleanupSpec.inputType, mCleanupSpec.overrideStyle,
                    mPrivacySensitive);
            });
            return future.get(CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            Log.w(TAG, "cleanup timed out after " + CLEANUP_TIMEOUT_MS + "ms, keeping raw text");
            return null;
        } catch (final Exception e) {
            Log.e(TAG, "cleanup failed, keeping raw text", e);
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    private void completeFinalize(final int swapMode, final String raw, final String cleanedOrNull) {
        if (mFinalizeCompleted) return;
        mFinalizeCompleted = true;
        mLatinIME.mHandler.removeCallbacks(mCleanupTimeout);
        if (mFinished || mCancelled) return;
        final String cleaned = cleanedOrNull == null || cleanedOrNull.trim().isEmpty()
            ? raw : cleanedOrNull;
        try {
            applySwap(swapMode, raw, cleaned);
        } catch (final Exception e) {
            Log.e(TAG, "failed to apply cleaned text, keeping raw text", e);
        }
        if (!mPrivacySensitive && !raw.trim().isEmpty()) {
            TranscriptionStorage.save(mLatinIME, raw, cleaned,
                System.currentTimeMillis() - mStartTimeMs, null, mPrivacySensitive);
        }
        markFinished(false);
    }

    private void applySwap(final int swapMode, final String raw, final String cleaned) {
        final boolean unchanged = cleaned.equals(raw);
        switch (swapMode) {
        case SWAP_COMPOSING:
            if (unchanged) {
                finishSegment();
            } else {
                swapComposingSpan(raw, cleaned);
            }
            break;
        case SWAP_COMMITTED:
            if (!unchanged) {
                swapCommittedText(raw, cleaned);
            }
            break;
        default:
            applyFallback(raw, cleaned, unchanged);
            break;
        }
    }

    /**
     * The whole streamed output is still one open composing span, so the cleaned text can replace
     * it atomically without any cursor arithmetic.
     */
    private void swapComposingSpan(final String raw, final String cleaned) {
        final RichInputConnection connection = mLatinIME.getRichInputConnection();
        if (connection == null) return;
        if (!textBeforeCursorMatches(connection, raw)) {
            Log.w(TAG, "composing span no longer matches the streamed text, keeping raw text");
            finishSegment();
            return;
        }
        connection.beginBatchEdit();
        final boolean applied = connection.setComposingText(cleaned, 1);
        connection.finishComposingText();
        connection.endBatchEdit();
        if (!applied) {
            Log.w(TAG, "editor did not honour the cleaned composing span");
        }
        mCommitted.setLength(0);
        mCommitted.append(cleaned);
        mComposing.setLength(0);
    }

    /**
     * Earlier segments were already finalized, so the swap needs a destructive delete. Only do it
     * when the text before the cursor is still exactly what this session inserted.
     */
    private void swapCommittedText(final String raw, final String cleaned) {
        final RichInputConnection connection = mLatinIME.getRichInputConnection();
        if (connection == null) return;
        if (!textBeforeCursorMatches(connection, raw)) {
            Log.w(TAG, "editor content changed under us, keeping raw streamed text");
            return;
        }
        connection.beginBatchEdit();
        connection.deleteTextBeforeCursor(raw.length());
        connection.commitText(cleaned, 1);
        connection.endBatchEdit();
        mCommitted.setLength(0);
        mCommitted.append(cleaned);
    }

    /**
     * Batch-fallback: the tail of the stream has not been inserted yet, so in the common case
     * (nothing in the editor yet) the cleaned text is simply committed once.
     */
    private void applyFallback(final String raw, final String cleaned, final boolean unchanged) {
        final String remainder = raw.length() > mFallbackTextInEditor
            ? raw.substring(mFallbackTextInEditor) : "";
        if (unchanged) {
            if (!remainder.isEmpty()) {
                mLatinIME.onTextInput(remainder);
            }
            return;
        }
        if (mFallbackTextInEditor == 0) {
            mLatinIME.onTextInput(cleaned);
            return;
        }
        final String prefix = raw.substring(0, mFallbackTextInEditor);
        final RichInputConnection connection = mLatinIME.getRichInputConnection();
        if (connection == null || !textBeforeCursorMatches(connection, prefix)) {
            Log.w(TAG, "editor content changed under us, keeping raw streamed text");
            if (!remainder.isEmpty()) {
                mLatinIME.onTextInput(remainder);
            }
            return;
        }
        connection.beginBatchEdit();
        connection.deleteTextBeforeCursor(prefix.length());
        connection.commitText(cleaned, 1);
        connection.endBatchEdit();
        mCommitted.setLength(0);
        mCommitted.append(cleaned);
        mFallbackTextInEditor = cleaned.length();
    }

    private static boolean textBeforeCursorMatches(final RichInputConnection connection,
            final String expected) {
        final CharSequence actual = connection.getTextBeforeCursor(expected.length(), 0);
        return actual != null && expected.contentEquals(actual);
    }

    private void markFinished(final boolean cancelled) {
        if (mFinished) return;
        mFinished = true;
        mLatinIME.mHandler.removeCallbacks(mFinalizeTimeout);
        mLatinIME.mHandler.removeCallbacks(mCleanupTimeout);
        mLatinIME.mHandler.removeCallbacks(mTypingAnimator);
        mTypingQueue.setLength(0);
        mListener.onStreamingFinished(cancelled);
    }

    private void appendDelta(final String delta) {
        if (delta == null || delta.isEmpty()) return;
        if (needsSeparator(delta.charAt(0))) {
            mComposing.append(' ');
        }
        mComposing.append(delta);
    }

    private void queueTyping(final int fromComposingStart) {
        if (mBatchFallback) {
            applyComposing();
            return;
        }
        for (int i = Math.max(fromComposingStart, mRevealed.length()); i < mComposing.length(); i++) {
            mTypingQueue.append(mComposing.charAt(i));
        }
        if (mTypingQueue.length() > 0) {
            mLatinIME.mHandler.removeCallbacks(mTypingAnimator);
            mLatinIME.mHandler.post(mTypingAnimator);
        }
    }

    private boolean needsSeparator(final char first) {
        if (!Character.isLetterOrDigit(first)) return false;
        final char last;
        if (mComposing.length() > 0) {
            last = mComposing.charAt(mComposing.length() - 1);
        } else if (mCommitted.length() > 0) {
            last = mCommitted.charAt(mCommitted.length() - 1);
        } else {
            return false;
        }
        return !Character.isWhitespace(last);
    }

    private int fullTextLength() {
        return mCommitted.length() + mComposing.length();
    }

    private void flushTyping() {
        mLatinIME.mHandler.removeCallbacks(mTypingAnimator);
        if (mTypingQueue.length() == 0) return;
        mRevealed.append(mTypingQueue);
        mTypingQueue.setLength(0);
        applyComposingRevealed();
    }

    private void applyComposingRevealed() {
        final RichInputConnection connection = mLatinIME.getRichInputConnection();
        if (connection == null) return;
        connection.beginBatchEdit();
        final boolean applied = connection.setComposingText(mRevealed.toString(), 1);
        connection.endBatchEdit();
        if (!applied) {
            enterBatchFallback();
        }
    }

    private boolean alreadyEndsWith(final String text) {
        final String candidate = normalize(text);
        if (candidate.isEmpty()) return false;
        return normalize(mCommitted.toString() + mComposing.toString()).endsWith(candidate);
    }

    private static String normalize(final String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private void applyComposing() {
        if (mBatchFallback) return;
        final RichInputConnection connection = mLatinIME.getRichInputConnection();
        if (connection == null) return;
        connection.beginBatchEdit();
        final boolean applied = connection.setComposingText(mComposing.toString(), 1);
        connection.endBatchEdit();
        if (!applied) {
            enterBatchFallback();
        }
    }

    /**
     * The editor committed instead of honouring a composing region. Stop touching the composing
     * span for the rest of the session and commit the remaining text once at the end, otherwise
     * every revision would be appended again.
     */
    private void enterBatchFallback() {
        Log.w(TAG, "editor ignored composing region, switching to batch-only streaming");
        mLatinIME.mHandler.removeCallbacks(mTypingAnimator);
        mTypingQueue.setLength(0);
        mBatchFallback = true;
        mCommitted.append(mRevealed);
        mRevealed.setLength(0);
        mCommitted.append(mComposing);
        mComposing.setLength(0);
        mFallbackTextInEditor = mCommitted.length();
        final RichInputConnection connection = mLatinIME.getRichInputConnection();
        if (connection == null) return;
        connection.beginBatchEdit();
        connection.finishComposingText();
        connection.endBatchEdit();
    }

    private void finishSegment() {
        if (mComposing.length() == 0) return;
        flushTyping();
        if (!mBatchFallback) {
            final RichInputConnection connection = mLatinIME.getRichInputConnection();
            if (connection != null) {
                connection.beginBatchEdit();
                connection.finishComposingText();
                connection.endBatchEdit();
            }
        }
        mCommitted.append(mRevealed);
        mRevealed.setLength(0);
        mComposing.setLength(0);
    }

    private void clearComposingRegion() {
        mLatinIME.mHandler.removeCallbacks(mTypingAnimator);
        mTypingQueue.setLength(0);
        if (!mBatchFallback && (mComposing.length() > 0 || mRevealed.length() > 0)) {
            final RichInputConnection connection = mLatinIME.getRichInputConnection();
            if (connection != null) {
                connection.beginBatchEdit();
                connection.setComposingText("", 1);
                connection.finishComposingText();
                connection.endBatchEdit();
            }
        }
        mRevealed.setLength(0);
        mComposing.setLength(0);
    }
}
