/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import com.velavoice.sdk.VelaTranscriber;
import com.velavoice.sdk.ScribeInput;
import com.velavoice.sdk.StreamingPipeline;
import com.velavoice.sdk.ui.VoiceRecordingPane;
import com.velavoice.sdk.VelaRecordingCallback;
import com.velavoice.sdk.TranscriptionResult;
import com.velavoice.sdk.VelaException;
import com.velavoice.sdk.cleaner.CleanerConfig;
import com.velavoice.sdk.cleaner.DictionaryKeywords;
import com.velavoice.sdk.cleaner.PersonalDictionary;

import helium314.keyboard.settings.TranscriptionStorage;
import helium314.keyboard.latin.settings.VelaApiKey;
import helium314.keyboard.latin.settings.VelaApiKeyStore;

import kotlin.Pair;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.event.Event;
import helium314.keyboard.keyboard.clipboard.ClipboardHistoryView;
import helium314.keyboard.keyboard.emoji.EmojiPalettesView;
import helium314.keyboard.keyboard.internal.KeyboardState;
import helium314.keyboard.keyboard.internal.LayoutDirective;
import helium314.keyboard.keyboard.internal.ShiftMode;
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParserKt;
import helium314.keyboard.latin.CapsMode;
import helium314.keyboard.latin.InputView;
import helium314.keyboard.latin.KeyboardWrapperView;
import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.Defaults;
import helium314.keyboard.latin.settings.SettingsKt;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.suggestions.SuggestionStripView;
import helium314.keyboard.latin.utils.CapsModeUtils;
import helium314.keyboard.latin.utils.FloatingKeyboardUtils;
import helium314.keyboard.latin.utils.FoldableUtils;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.LanguageOnSpacebarUtils;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.latin.utils.RecapitalizeMode;
import helium314.keyboard.latin.utils.ResourceUtils;
import helium314.keyboard.latin.utils.ScriptUtils;
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional;
import helium314.keyboard.latin.utils.ToolbarMode;

public final class KeyboardSwitcher implements KeyboardState.SwitchActions {
    private static final String TAG = KeyboardSwitcher.class.getSimpleName();

    private InputView mCurrentInputView;
    private View mSavedInputView; // saved main input view while voice pane is shown
    private VelaTranscriber velaTranscriber;
    private VelaStreamingSession velaStreamingSession;
    private boolean mIsVelaRecordingCancelled = false;
    private String mCachedVelaModelPath = null;
    private Boolean mCachedVelaLlmToggle = null;
    private Boolean mCachedVelaScribeToggle = null;
    private String mCachedVelaLlmModelPath = null;
    private String mCachedVelaLanguage = null;
    private int mCachedVelaThreads = -1;
    private java.util.List<String> mCachedVelaCustomFillers = null;
    private KeyboardWrapperView mKeyboardViewWrapper;
    private View mMainKeyboardFrame;
    private MainKeyboardView mKeyboardView;
    private EmojiPalettesView mEmojiPalettesView;
    private View mEmojiTabStripView;
    private LinearLayout mClipboardStripView;
    private HorizontalScrollView mClipboardStripScrollView;
    private SuggestionStripView mSuggestionStripView;
    private FrameLayout mStripContainer;
    private ClipboardHistoryView mClipboardHistoryView;
    private TextView mFakeToastView;
    private ImageView mBackgroundGatheringIndicator;
    private LatinIME mLatinIME;
    private RichInputMethodManager mRichImm;
    private boolean mIsHardwareAcceleratedDrawingEnabled;

    private KeyboardState mState;

    private KeyboardLayoutSet mKeyboardLayoutSet;

    private KeyboardTheme mKeyboardTheme;
    private Context mThemeContext;
    private int mCurrentUiMode;
    private int mCurrentOrientation;
    private int mCurrentDpi;
    private boolean mThemeNeedsReload;

    @SuppressLint("StaticFieldLeak") // this is a keyboard, we want to keep it alive in background
    private static final KeyboardSwitcher sInstance = new KeyboardSwitcher();

    public static KeyboardSwitcher getInstance() {
        return sInstance;
    }

    private KeyboardSwitcher() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final LatinIME latinIme) {
        sInstance.initInternal(latinIme);
    }

    private void initInternal(final LatinIME latinIme) {
        mLatinIME = latinIme;
        mRichImm = RichInputMethodManager.getInstance();
        mState = new KeyboardState(this);
        mIsHardwareAcceleratedDrawingEnabled = mLatinIME.enableHardwareAcceleration();
    }

    public void updateKeyboardTheme(@NonNull Context displayContext) {
        final boolean themeUpdated = updateKeyboardThemeAndContextThemeWrapper(
                displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        if (themeUpdated) {
            Settings settings = Settings.getInstance();
            settings.loadSettings(displayContext, settings.getCurrent().mLocale, settings.getCurrent().mInputAttributes);
            if (mKeyboardView != null)
                mLatinIME.setInputView(onCreateInputView(displayContext, mIsHardwareAcceleratedDrawingEnabled));
        } else if (mCurrentInputView != null && mLatinIME.hasSuggestionStripView()
                    == (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN || mLatinIME.isEmojiSearch())) {
            mLatinIME.updateSuggestionStripView(mCurrentInputView);
        }
    }

    private boolean updateKeyboardThemeAndContextThemeWrapper(final Context context, final KeyboardTheme keyboardTheme) {
        final Resources res = context.getResources();
        if (mThemeNeedsReload
                || mThemeContext == null
                || !keyboardTheme.equals(mKeyboardTheme)
                || mCurrentDpi != res.getDisplayMetrics().densityDpi
                || mCurrentOrientation != res.getConfiguration().orientation
                || (mCurrentUiMode & Configuration.UI_MODE_NIGHT_MASK) != (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                || !mThemeContext.getResources().equals(res)
                || Settings.getValues().mColors.haveColorsChanged(context)) {
            mThemeNeedsReload = false;
            mKeyboardTheme = keyboardTheme;
            mThemeContext = new ContextThemeWrapper(context, keyboardTheme.mStyleId);
            mCurrentUiMode = res.getConfiguration().uiMode;
            mCurrentOrientation = res.getConfiguration().orientation;
            mCurrentDpi = res.getDisplayMetrics().densityDpi;
            KeyboardLayoutSet.Companion.onKeyboardThemeChanged();
            return true;
        }
        return false;
    }

    public void loadKeyboard(final EditorInfo editorInfo, final SettingsValues settingsValues,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState,
            KeyboardLayoutSet.InternalAction internalAction) {
        final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(
                mThemeContext, editorInfo);
        final int keyboardWidth = ResourceUtils.getKeyboardWidth(mThemeContext, settingsValues);
        final int keyboardHeight = ResourceUtils.getKeyboardHeight(mThemeContext.getResources(), settingsValues);
        mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                .setSubtype(mRichImm.getCurrentSubtype())
                .setVoiceInputKeyEnabled(settingsValues.mShowsVoiceInputKey)
                .setNumberRowEnabled(settingsValues.mShowsNumberRow)
                .setNumberRowInSymbolsEnabled(settingsValues.mShowsNumberRowInSymbols)
                .setLanguageSwitchKeyEnabled(settingsValues.isLanguageSwitchKeyEnabled())
                .setEmojiKeyEnabled(settingsValues.mShowsEmojiKey)
                .setSplitLayoutEnabled(settingsValues.mIsSplitKeyboardEnabled)
                .setOneHandedModeEnabled(settingsValues.mOneHandedModeEnabled)
                .setInternalAction(internalAction)
                .build();
        try {
            mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, settingsValues.mOneHandedModeEnabled);
        } catch (KeyboardLayoutSet.Companion.KeyboardLayoutSetException e) {
            Log.e(TAG, "loading keyboard failed: " + e.getKeyboardId(), e.getCause());
            try {
                final InputMethodSubtype defaults = SubtypeUtilsAdditional.INSTANCE.createDefaultSubtype(mRichImm.getCurrentSubtypeLocale());
                mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                        .setSubtype(RichInputMethodSubtype.Companion.get(defaults))
                        .setNumberRowEnabled(settingsValues.mShowsNumberRow)
                        .setNumberRowInSymbolsEnabled(settingsValues.mShowsNumberRowInSymbols)
                        .setLanguageSwitchKeyEnabled(settingsValues.isLanguageSwitchKeyEnabled())
                        .setEmojiKeyEnabled(settingsValues.mShowsEmojiKey)
                        .build();
                mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, false);
                showToast("error loading the keyboard, falling back to defaults", false);
            } catch (KeyboardLayoutSet.Companion.KeyboardLayoutSetException e2) {
                Log.e(TAG, "even fallback to defaults failed: " + e2.getKeyboardId(), e2.getCause());
            }
        }
    }

    public void saveKeyboardState() {
        if (getKeyboard() != null || isShowingEmojiPalettes() || isShowingClipboardHistory()) {
            mState.onSaveKeyboardState();
        }
    }

    private boolean mIsVelaLoading = false;
    private int mRecordingSeconds = 0;
    private Handler mTimerHandler = null;
    /** Privacy verdict captured when the current voice session started (map #72, ticket #78). */
    private boolean mSessionStartedPrivacySensitive = false;
    private Handler mMainHandler = new Handler(Looper.getMainLooper());

    public void stopVelaRecording() {
        mIsVelaRecordingCancelled = true;
        stopTimer();
        if (velaStreamingSession != null) {
            // input is ending: keep what the user already sees, just close the composing span
            velaStreamingSession.abortKeepingText();
        }
        if (velaTranscriber != null) {
            velaTranscriber.stopRecording(false);
        }
        // YAPS: stopActiveRecording restores the keyboard; the input view was not replaced.
        YapsUiManager.getInstance().stopActiveRecording();
    }

    /** Explicit user cancel (the X button). Streamed text is removed, instant behaves as before. */
    public void cancelVelaRecording() {
        mIsVelaRecordingCancelled = true;
        stopTimer();
        if (velaStreamingSession != null) {
            velaStreamingSession.cancel();
            YapsUiManager.getInstance().stopActiveRecording();
            return;
        }
        if (velaTranscriber != null) {
            velaTranscriber.stopRecording(false);
        }
        YapsUiManager.getInstance().stopActiveRecording();
    }

    public void confirmVelaRecording() {
        stopTimer();
        if (velaStreamingSession != null) {
            velaStreamingSession.stop();
            return;
        }
        if (velaTranscriber != null) {
            velaTranscriber.stopRecording(true);
        }
    }

    public void releaseVelaTranscriber() {
        mIsVelaRecordingCancelled = true;
        stopTimer();
        if (velaStreamingSession != null) {
            velaStreamingSession.abortKeepingText();
            velaStreamingSession.release();
            velaStreamingSession = null;
        }
        if (velaTranscriber != null) {
            velaTranscriber.cancelRecording();
            velaTranscriber.release();
            velaTranscriber = null;
        }
        mCachedVelaModelPath = null;
        mCachedVelaLlmToggle = null;
        mCachedVelaScribeToggle = null;
        mCachedVelaLlmModelPath = null;
        mCachedVelaLanguage = null;
        mCachedVelaThreads = -1;
        mCachedVelaCustomFillers = null;
        mSavedInputView = null;
        YapsUiManager.getInstance().stopActiveRecording();
    }

    /** Called when a key is tapped mid-stream: finalize the composing span and tear the stream down. */
    public void abortVelaStreamingOnUserInput() {
        if (velaStreamingSession == null) return;
        stopTimer();
        velaStreamingSession.abortKeepingText();
    }

    public boolean isVelaStreaming() {
        return velaStreamingSession != null && velaStreamingSession.isActive();
    }

    /** True while a Vela voice session may still be recording, loading or streaming (map #72, ticket #78). */
    public boolean isVelaSessionActive() {
        return mTimerHandler != null || mIsVelaLoading || isVelaStreaming();
    }

    /**
     * Re-evaluate the privacy verdict while a session runs (map #72, ticket #78).
     * Focus can change without the input finishing; if the verdict flips to
     * sensitive after a non-sensitive start, abort the session (fail closed).
     * A session already started sensitive is never touched, and outside an
     * active session this is a no-op so hot paths stay cheap.
     */
    public void recheckSessionPrivacy(final EditorInfo editorInfo) {
        if (!isVelaSessionActive()) return;
        if (mSessionStartedPrivacySensitive) return;
        if (!com.velavoice.sdk.PrivacyGuard.isPrivacySensitiveEditor(editorInfo, true)) return;
        cancelVelaRecording();
    }

    private void startTimer(final VoiceRecordingPane voicePane) {
        stopTimer();
        mRecordingSeconds = 0;
        mTimerHandler = new Handler(Looper.getMainLooper());
        voicePane.updateTimer(0);
        mTimerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mIsVelaRecordingCancelled) return;
                mRecordingSeconds++;
                voicePane.updateTimer(mRecordingSeconds);
                mTimerHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void startYapsTimer() {
        stopTimer();
        mRecordingSeconds = 0;
        mTimerHandler = new Handler(Looper.getMainLooper());
        mTimerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mIsVelaRecordingCancelled) return;
                mRecordingSeconds++;
                mTimerHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void stopTimer() {
        if (mTimerHandler != null) {
            mTimerHandler.removeCallbacksAndMessages(null);
            mTimerHandler = null;
        }
    }

	/**
	 * Long-press voice key intercept: force Scribe mode regardless text selection.
	 */
	public void showVelaVoicePane(final LatinIME latinIME, final boolean forceScribe) {
		showVelaVoicePaneInternal(latinIME, forceScribe);
	}

    public void showVelaVoicePane(final LatinIME latinIME) {
        showVelaVoicePaneInternal(latinIME, false);
    }

    /**
     * True when the cached transcriber was built with exactly this configuration.
     * Every buildVelaTranscriber() parameter must participate in the key: a change
     * to language/threads/custom fillers/models between recordings must force a
     * rebuild instead of silently reusing the stale instance (OCR finding).
     */
    boolean cachedTranscriberConfigMatches(final String modelPath, final String llmModelPath,
            final boolean useLlm, final boolean effectiveScribe, final String language,
            final int threads, final java.util.List<String> customFillers) {
        return java.util.Objects.equals(modelPath, mCachedVelaModelPath)
            && java.util.Objects.equals(llmModelPath, mCachedVelaLlmModelPath)
            && Boolean.valueOf(useLlm).equals(mCachedVelaLlmToggle)
            && Boolean.valueOf(effectiveScribe).equals(mCachedVelaScribeToggle)
            && java.util.Objects.equals(language, mCachedVelaLanguage)
            && threads == mCachedVelaThreads
            && java.util.Objects.equals(customFillers, mCachedVelaCustomFillers);
    }

    private void showVelaVoicePaneInternal(final LatinIME latinIME, final boolean forceScribe) {
        // --- Read preferences with in-memory caching ---
        SharedPreferences prefs = Settings.getInstance().getPrefs();
        
        String resolvedModelPath = prefs.getString(Settings.PREF_VELA_MODEL_PATH,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_MODEL_PATH);
        if (resolvedModelPath == null || resolvedModelPath.isEmpty() || !new java.io.File(resolvedModelPath).exists()) {
            String sharedPath = helium314.keyboard.settings.ModelDownloadHelper.getSharedModelPath(latinIME, "whisper");
            if (sharedPath != null) {
                resolvedModelPath = sharedPath;
            }
        }
        final String modelPath = resolvedModelPath;

        final EditorInfo editorInfo = latinIME != null ? latinIME.getCurrentInputEditorInfo() : null;
        final boolean privacySensitive = isPrivacySensitiveEditor(editorInfo);
        mSessionStartedPrivacySensitive = privacySensitive;
        final boolean useLlm = !privacySensitive && prefs.getBoolean(Settings.PREF_VELA_LLM_TOGGLE,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_LLM_TOGGLE);
        final boolean scribeEnabled = !privacySensitive && prefs.getBoolean(Settings.PREF_VELA_SCRIBE_ENABLED,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_SCRIBE_ENABLED);
        final boolean effectiveScribe = !privacySensitive && (forceScribe || scribeEnabled);

        final String llmModelPath = resolveLlmModelPath(latinIME, prefs);

        final String language = prefs.getString(Settings.PREF_VELA_LANGUAGE,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_LANGUAGE);
        final int threads = prefs.getInt(Settings.PREF_VELA_THREADS,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_THREADS);
        final java.util.List<String> customFillers = parseCustomFillers(prefs);

        mIsVelaRecordingCancelled = false;
        final String uiStyle = prefs.getString(Settings.PREF_VELA_UI_STYLE, Defaults.PREF_VELA_UI_STYLE);
        final boolean isYaps = "yaps".equals(uiStyle);

        final String streamingMode = prefs.getString(Settings.PREF_VELA_STREAMING_MODE,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_STREAMING_MODE);
        final String transcriptionMode = prefs.getString(Settings.PREF_VELA_TRANSCRIPTION_MODE,
                helium314.keyboard.latin.settings.Defaults.PREF_VELA_TRANSCRIPTION_MODE);
        final boolean wantsCloud = !"local".equals(transcriptionMode);
        if (("streamed".equals(streamingMode) || wantsCloud)
                && startVelaStreaming(latinIME, prefs, modelPath, language, threads, isYaps)) {
            return;
        }

        // ===== YAPS MODE: In-toolbar recording (no full-screen VoiceRecordingPane) =====
        if (isYaps) {
            // Don't set mSavedInputView — we're NOT replacing the input view in YAPS mode,
            // just overlaying toolbar/overlay on the existing keyboard. This prevents
            // showMainKeyboard from calling setInputView() unnecessarily later, which
            // would trigger a re-layout and destabilize onComputeInsets.
            final String providerTitle = "gemini".equals(transcriptionMode) ? ("streamed".equals(streamingMode) ? "Gemini 3.5 Live" : "Gemini 3.5")
                : "groq".equals(transcriptionMode) ? "Groq"
                : "openai".equals(transcriptionMode) ? "OpenAI"
                : "custom".equals(transcriptionMode) ? "Custom"
                : "Local";
        final String yapsLabel = providerTitle + "...";
            YapsUiManager.getInstance().startActiveRecording(yapsLabel);
            startVelaTranscriberForYaps(latinIME, prefs, modelPath, useLlm, llmModelPath,
                language, threads, customFillers, forceScribe, privacySensitive);
            return;
        }

        // ===== VELA MODE: Full-pane recording (existing behavior) =====
        mSavedInputView = mCurrentInputView;

        final VoiceRecordingPane voicePane = new VoiceRecordingPane(latinIME);
        final float density = latinIME.getResources().getDisplayMetrics().density;
        final int maxHeightPx = (int)(280 * density);

        final FrameLayout wrapper = new FrameLayout(latinIME);
        wrapper.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        wrapper.addView(voicePane, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            maxHeightPx,
            Gravity.BOTTOM));
        latinIME.setInputView(wrapper);

        voicePane.getStopCleanButton().setEnabled(false);
        voicePane.getStopRawButton().setEnabled(false);
        final String providerTitle = "gemini".equals(transcriptionMode) ? ("streamed".equals(streamingMode) ? "Gemini 3.5 Live" : "Gemini 3.5")
                : "groq".equals(transcriptionMode) ? "Groq"
                : "openai".equals(transcriptionMode) ? "OpenAI"
                : "custom".equals(transcriptionMode) ? "Custom Provider"
                : "Voice";
        final String loadingLabel = "local".equals(transcriptionMode)
                ? "Loading voice model..."
                : "Connecting to " + providerTitle + "...";
        voicePane.getStatusText().setText(loadingLabel);

        // --- Wire up callbacks ---
        voicePane.setOnStopCleanListener(() -> {
            stopTimer();
            voicePane.getStatusText().setText("Processing...");
            if (velaTranscriber != null) {
                velaTranscriber.stopRecording(true);
            }
            return kotlin.Unit.INSTANCE;
        });

        voicePane.setOnStopRawListener(() -> {
            stopTimer();
            voicePane.getStatusText().setText("Processing...");
            if (velaTranscriber != null) {
                velaTranscriber.stopRecording(false);
            }
            return kotlin.Unit.INSTANCE;
        });

        voicePane.setOnCancelListener(() -> {
            mIsVelaRecordingCancelled = true;
            stopTimer();
            if (velaTranscriber != null) {
                velaTranscriber.cancelRecording();
            }
            showMainKeyboard(latinIME);
            return kotlin.Unit.INSTANCE;
        });

        // --- Check if we can reuse cached transcriber ---
        final boolean canReuseTranscriber = velaTranscriber != null
            && cachedTranscriberConfigMatches(modelPath, llmModelPath, useLlm, effectiveScribe,
                language, threads, customFillers);

        if (canReuseTranscriber) {
            voicePane.getStopCleanButton().setEnabled(true);
            voicePane.getStopRawButton().setEnabled(true);
            final String recLabel = "gemini".equals(transcriptionMode) ? "Listening (Gemini 3.6)..." : "Recording...";
                    voicePane.getStatusText().setText(recLabel);
            voicePane.resetDisplay();
            startTimer(voicePane);

            try {
                velaTranscriber.startRecording(buildVelaCallbacks(latinIME, voicePane, false, privacySensitive),
                    buildScribeInput(latinIME, prefs, forceScribe));
            } catch (Exception e) {
                showToast("Failed to start voice recording: " + e.getMessage(), false);
                showMainKeyboard(latinIME);
            }
        } else {
            // Build new transcriber on background thread
            mIsVelaLoading = true;
            new Thread(() -> {
                try {
                    if (velaTranscriber != null) {
                        velaTranscriber.release();
                        velaTranscriber = null;
                    }
                    VelaTranscriber transcriber = buildVelaTranscriber(latinIME, prefs,
                        modelPath, useLlm, llmModelPath, language, threads, customFillers,
                        forceScribe, privacySensitive);

                    voicePane.post(() -> {
                        mIsVelaLoading = false;
                        if (mIsVelaRecordingCancelled) {
                            transcriber.release();
                            return;
                        }
                        velaTranscriber = transcriber;
                        mCachedVelaModelPath = modelPath;
                        mCachedVelaLlmToggle = useLlm;
                        mCachedVelaScribeToggle = effectiveScribe;
                        mCachedVelaLlmModelPath = llmModelPath;
                        mCachedVelaLanguage = language;
                        mCachedVelaThreads = threads;
                        mCachedVelaCustomFillers = customFillers;

                        voicePane.getStopCleanButton().setEnabled(true);
                        voicePane.getStopRawButton().setEnabled(true);
                        voicePane.getStatusText().setText("Recording...");
                        voicePane.resetDisplay();
                        startTimer(voicePane);

                        try {
                            velaTranscriber.startRecording(buildVelaCallbacks(latinIME, voicePane, false, privacySensitive),
                                buildScribeInput(latinIME, prefs, forceScribe));
                        } catch (Exception e) {
                            showToast("Failed to start voice recording: " + e.getMessage(), false);
                            showMainKeyboard(latinIME);
                        }
                    });
                } catch (final Exception e) {
                    voicePane.post(() -> {
                        mIsVelaLoading = false;
                        showToast("Failed to load voice model: " + e.getMessage(), false);
                        showMainKeyboard(latinIME);
                    });
                }
            }).start();
        }
    }

    /** Yaps-mode entry point: load/reuse transcriber and wire up YapsUi callbacks */
    private void startVelaTranscriberForYaps(final LatinIME latinIME,
            final SharedPreferences prefs, final String modelPath, final boolean useLlm,
            final String llmModelPath, final String language, final int threads,
            final java.util.List<String> customFillers, final boolean forceScribe,
            final boolean privacySensitive) {
        final boolean scribeEnabled = !privacySensitive && prefs.getBoolean(Settings.PREF_VELA_SCRIBE_ENABLED,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_SCRIBE_ENABLED);
        final boolean effectiveScribe = !privacySensitive && (forceScribe || scribeEnabled);
        if (velaTranscriber != null && cachedTranscriberConfigMatches(modelPath, llmModelPath,
                useLlm, effectiveScribe, language, threads, customFillers)) {
            YapsUiManager.getInstance().updateLanguageText(language);
            startYapsTimer();
            try {
                velaTranscriber.startRecording(buildVelaCallbacks(latinIME, null, true, privacySensitive),
                    buildScribeInput(latinIME, prefs, forceScribe));
            } catch (Exception e) {
                showToast("Failed to start voice recording: " + e.getMessage(), false);
                YapsUiManager.getInstance().stopActiveRecording();
            }
            return;
        }

        mIsVelaLoading = true;
        new Thread(() -> {
            try {
                if (velaTranscriber != null) {
                    velaTranscriber.release();
                    velaTranscriber = null;
                }
                VelaTranscriber transcriber = buildVelaTranscriber(latinIME, prefs,
                    modelPath, useLlm, llmModelPath, language, threads, customFillers,
                    forceScribe, privacySensitive);

                latinIME.mHandler.post(() -> {
                    mIsVelaLoading = false;
                    if (mIsVelaRecordingCancelled) {
                        transcriber.release();
                        return;
                    }
                    velaTranscriber = transcriber;
                    mCachedVelaModelPath = modelPath;
                    mCachedVelaLlmToggle = useLlm;
                    mCachedVelaScribeToggle = effectiveScribe;
                    mCachedVelaLlmModelPath = llmModelPath;
                    mCachedVelaLanguage = language;
                    mCachedVelaThreads = threads;
                    mCachedVelaCustomFillers = customFillers;
                    YapsUiManager.getInstance().updateLanguageText(language);
                    startYapsTimer();
                    try {
                        velaTranscriber.startRecording(buildVelaCallbacks(latinIME, null, true, privacySensitive),
                            buildScribeInput(latinIME, prefs, forceScribe));
                    } catch (Exception e) {
                        showToast("Failed to start voice recording: " + e.getMessage(), false);
                        YapsUiManager.getInstance().stopActiveRecording();
                    }
                });
            } catch (final Exception e) {
                latinIME.mHandler.post(() -> {
                    mIsVelaLoading = false;
                    showToast("Failed to load voice model: " + e.getMessage(), false);
                });
            }
        }).start();
    }

    /**
     * Streaming entry point (PREF_VELA_STREAMING_MODE = "streamed"). Returns false when streaming
     * cannot be started, in which case the caller continues with the one-shot instant path.
     */
    private boolean startVelaStreaming(final LatinIME latinIME, final SharedPreferences prefs,
            final String modelPath, final String language, final int threads, final boolean isYaps) {
        final boolean modelAvailable = modelPath != null && !modelPath.isEmpty()
            && new java.io.File(modelPath).exists();
        final String transcriptionMode = prefs.getString(Settings.PREF_VELA_TRANSCRIPTION_MODE,
                helium314.keyboard.latin.settings.Defaults.PREF_VELA_TRANSCRIPTION_MODE);
        final String streamingMode = prefs.getString(Settings.PREF_VELA_STREAMING_MODE,
                helium314.keyboard.latin.settings.Defaults.PREF_VELA_STREAMING_MODE);
        final boolean privacySensitive = isPrivacySensitiveEditor(latinIME.getCurrentInputEditorInfo());

        boolean wantsCloud = !"local".equals(transcriptionMode);
        if (wantsCloud && privacySensitive) {
            if (!modelAvailable) {
                showToast(latinIME.getString(R.string.voice_streaming_privacy_no_local), false);
                return false;
            }
            wantsCloud = false;
        }

        String resolvedApiKey = null;
        String resolvedModel = null;
        String resolvedEndpoint = null;

        SharedPreferences companionPrefs = null;
        try {
            final android.content.Context companionContext = latinIME.createPackageContext(
                    "com.velavoice.app", android.content.Context.CONTEXT_IGNORE_SECURITY);
            companionPrefs = companionContext.getSharedPreferences(
                    "com.velavoice.app_preferences", android.content.Context.MODE_PRIVATE);
        } catch (final Exception ignored) {
        }

        if ("gemini".equals(transcriptionMode)) {
            final boolean isStreamed = "streamed".equals(streamingMode);
            final String defaultGeminiModel = isStreamed
                    ? helium314.keyboard.latin.settings.Defaults.PREF_VELA_GEMINI_LIVE_MODEL
                    : helium314.keyboard.latin.settings.Defaults.PREF_VELA_GEMINI_MODEL;

            resolvedApiKey = VelaApiKeyStore.getRawApiKey(latinIME, VelaApiKey.GEMINI);
            if (resolvedApiKey == null) {
                resolvedApiKey = helium314.keyboard.latin.settings.Defaults.PREF_VELA_GEMINI_API_KEY;
            }
            resolvedModel = prefs.getString(Settings.PREF_VELA_GEMINI_MODEL, defaultGeminiModel);
            if (resolvedModel == null || resolvedModel.trim().isEmpty() || "gemini-3.5".equals(resolvedModel.trim()) || "3.5".equals(resolvedModel.trim()) || resolvedModel.contains("3.6")) {
                resolvedModel = defaultGeminiModel;
                prefs.edit().putString(Settings.PREF_VELA_GEMINI_MODEL, resolvedModel).apply();
            }
            resolvedEndpoint = "https://generativelanguage.googleapis.com";

            if ((resolvedApiKey == null || resolvedApiKey.trim().isEmpty()) && companionPrefs != null) {
                final String compKey = companionPrefs.getString("geminiApiKey", "");
                if (compKey != null && !compKey.trim().isEmpty()) {
                    resolvedApiKey = compKey.trim();
                    VelaApiKeyStore.setApiKey(latinIME, VelaApiKey.GEMINI, resolvedApiKey);
                }
                final String compModel = companionPrefs.getString("geminiModel", "");
                if (compModel != null && !compModel.trim().isEmpty()) {
                    resolvedModel = compModel.trim();
                    prefs.edit().putString(Settings.PREF_VELA_GEMINI_MODEL, resolvedModel).apply();
                }
            }
        } else if ("groq".equals(transcriptionMode)) {
            resolvedApiKey = VelaApiKeyStore.getRawApiKey(latinIME, VelaApiKey.GROQ);
            if (resolvedApiKey == null) {
                resolvedApiKey = helium314.keyboard.latin.settings.Defaults.PREF_VELA_GROQ_API_KEY;
            }
            resolvedModel = prefs.getString(Settings.PREF_VELA_GROQ_MODEL,
                    helium314.keyboard.latin.settings.Defaults.PREF_VELA_GROQ_MODEL);
            resolvedEndpoint = "https://api.groq.com/openai/v1/audio/transcriptions";

            if ((resolvedApiKey == null || resolvedApiKey.trim().isEmpty()) && companionPrefs != null) {
                final String compKey = companionPrefs.getString("groqApiKey", "");
                if (compKey != null && !compKey.trim().isEmpty()) {
                    resolvedApiKey = compKey.trim();
                    VelaApiKeyStore.setApiKey(latinIME, VelaApiKey.GROQ, resolvedApiKey);
                }
                final String compModel = companionPrefs.getString("groqModel", "");
                if (compModel != null && !compModel.trim().isEmpty()) {
                    resolvedModel = compModel.trim();
                    prefs.edit().putString(Settings.PREF_VELA_GROQ_MODEL, resolvedModel).apply();
                }
            }
        } else if ("custom".equals(transcriptionMode)) {
            resolvedApiKey = VelaApiKeyStore.getRawApiKey(latinIME, VelaApiKey.CUSTOM);
            if (resolvedApiKey == null) {
                resolvedApiKey = helium314.keyboard.latin.settings.Defaults.PREF_VELA_CUSTOM_API_KEY;
            }
            resolvedModel = prefs.getString(Settings.PREF_VELA_CUSTOM_MODEL,
                    helium314.keyboard.latin.settings.Defaults.PREF_VELA_CUSTOM_MODEL);
            resolvedEndpoint = prefs.getString(Settings.PREF_VELA_CUSTOM_ENDPOINT,
                    helium314.keyboard.latin.settings.Defaults.PREF_VELA_CUSTOM_ENDPOINT);

            if ((resolvedApiKey == null || resolvedApiKey.trim().isEmpty()) && companionPrefs != null) {
                final String compKey = companionPrefs.getString("customApiKey", "");
                if (compKey != null && !compKey.trim().isEmpty()) {
                    resolvedApiKey = compKey.trim();
                    VelaApiKeyStore.setApiKey(latinIME, VelaApiKey.CUSTOM, resolvedApiKey);
                }
                final String compModel = companionPrefs.getString("customModel", "");
                if (compModel != null && !compModel.trim().isEmpty()) {
                    resolvedModel = compModel.trim();
                    prefs.edit().putString(Settings.PREF_VELA_CUSTOM_MODEL, resolvedModel).apply();
                }
                final String compEndpoint = companionPrefs.getString("customEndpoint", "");
                if (compEndpoint != null && !compEndpoint.trim().isEmpty()) {
                    resolvedEndpoint = compEndpoint.trim();
                    prefs.edit().putString(Settings.PREF_VELA_CUSTOM_ENDPOINT, resolvedEndpoint).apply();
                }
            }
        } else {
            resolvedApiKey = VelaApiKeyStore.getRawApiKey(latinIME, VelaApiKey.OPENAI);
            if (resolvedApiKey == null) {
                resolvedApiKey = helium314.keyboard.latin.settings.Defaults.PREF_VELA_OPENAI_API_KEY;
            }
            resolvedModel = prefs.getString(Settings.PREF_VELA_OPENAI_MODEL,
                    helium314.keyboard.latin.settings.Defaults.PREF_VELA_OPENAI_MODEL);
            resolvedEndpoint = prefs.getString(Settings.PREF_VELA_OPENAI_ENDPOINT,
                    helium314.keyboard.latin.settings.Defaults.PREF_VELA_OPENAI_ENDPOINT);

            if ((resolvedApiKey == null || resolvedApiKey.trim().isEmpty()) && companionPrefs != null) {
                final String compKey = companionPrefs.getString("openaiApiKey", "");
                if (compKey != null && !compKey.trim().isEmpty()) {
                    resolvedApiKey = compKey.trim();
                    VelaApiKeyStore.setApiKey(latinIME, VelaApiKey.OPENAI, resolvedApiKey);
                }
                final String compModel = companionPrefs.getString("openaiModel", "");
                if (compModel != null && !compModel.trim().isEmpty()) {
                    resolvedModel = compModel.trim();
                    prefs.edit().putString(Settings.PREF_VELA_OPENAI_MODEL, resolvedModel).apply();
                }
                final String compEndpoint = companionPrefs.getString("openaiEndpoint", "");
                if (compEndpoint != null && !compEndpoint.trim().isEmpty()) {
                    resolvedEndpoint = compEndpoint.trim();
                    prefs.edit().putString(Settings.PREF_VELA_OPENAI_ENDPOINT, resolvedEndpoint).apply();
                }
            }
        }

        final String apiKey = resolvedApiKey;
        final String model = resolvedModel;
        final String endpoint = resolvedEndpoint;

        if (wantsCloud && (apiKey == null || apiKey.trim().isEmpty())) {
            showToast("No API key configured for " + transcriptionMode + ". Please enter key in Voice Settings.", false);
            return false;
        }
        if (!wantsCloud && !modelAvailable) {
            return false;
        }

        final boolean useCloud = wantsCloud;
        final String pipelineMode = useCloud ? "cloud" : "local";

        final StreamingPipeline.Builder builder = new StreamingPipeline.Builder(latinIME)
                .language(language)
                .threads(threads)
                .chunkDurationMs(200)
                .windowDurationMs(2000)
                .overlapMs(200)
                .privacySensitive(privacySensitive);
        if (modelAvailable) {
            builder.whisperModelPath(modelPath);
        }
        if (useCloud) {
            // Cloud upload contract (map #72 #77): the user's explicit cloud
            // transcription-mode choice is the consent; sensitive fields never
            // reach this branch (wantsCloud is forced false above).
            builder.consentToUpload(true);
            builder.apiKey(apiKey);
            // Ticket 88 (map #81): only encrypted transports carry API keys.
            // ws:// and http:// are rejected with a clear error instead of
            // silently falling back, so a misconfigured endpoint can never
            // leak credentials over cleartext.
            if (endpoint != null && !endpoint.isEmpty()) {
                if (endpoint.startsWith("wss://") || endpoint.startsWith("https://")) {
                    builder.endpoint(endpoint);
                } else {
                    showToast("Insecure transcription endpoint rejected. Use wss:// or https://.", false);
                    return false;
                }
            }
            if (model != null && !model.isEmpty()) {
                builder.model(model);
            }
        }
        final VelaStreamingSession.CleanupSpec cleanupSpec =
            buildCleanupSpec(latinIME, prefs, privacySensitive);

        if (isYaps) {
            final String yapsLabel = "gemini".equals(transcriptionMode) ? "Gemini 3.6..." : "Loading...";
            YapsUiManager.getInstance().startActiveRecording(yapsLabel);
            startStreamingSession(latinIME, builder, pipelineMode, transcriptionMode, privacySensitive, cleanupSpec,
                    null, isYaps);
            return true;
        }

        mSavedInputView = mCurrentInputView;

        final VoiceRecordingPane voicePane = new VoiceRecordingPane(latinIME);
        final float density = latinIME.getResources().getDisplayMetrics().density;
        final int maxHeightPx = (int)(280 * density);

        final FrameLayout wrapper = new FrameLayout(latinIME);
        wrapper.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        wrapper.addView(voicePane, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            maxHeightPx,
            Gravity.BOTTOM));
        latinIME.setInputView(wrapper);

        voicePane.getStopCleanButton().setEnabled(false);
        voicePane.getStopRawButton().setEnabled(false);
        voicePane.getStatusText().setText("Loading voice model...");

        voicePane.setOnStopCleanListener(() -> {
            stopTimer();
            voicePane.getStatusText().setText("Processing...");
            if (velaStreamingSession != null) {
                velaStreamingSession.stop();
            }
            return kotlin.Unit.INSTANCE;
        });

        voicePane.setOnStopRawListener(() -> {
            stopTimer();
            voicePane.getStatusText().setText("Processing...");
            if (velaStreamingSession != null) {
                velaStreamingSession.stop();
            }
            return kotlin.Unit.INSTANCE;
        });

        voicePane.setOnCancelListener(() -> {
            mIsVelaRecordingCancelled = true;
            stopTimer();
            if (velaStreamingSession != null) {
                velaStreamingSession.cancel();
            } else {
                showMainKeyboard(latinIME);
            }
            return kotlin.Unit.INSTANCE;
        });

        startStreamingSession(latinIME, builder, pipelineMode, transcriptionMode, privacySensitive, cleanupSpec,
                voicePane, isYaps);
        return true;
    }

    /**
     * StreamingPipeline bypasses the SDK's TextCleaner, so capture the same cleanup config and
     * editor context the instant path resolves and hand it to the session for the final pass.
     */
    private VelaStreamingSession.CleanupSpec buildCleanupSpec(final LatinIME latinIME,
            final SharedPreferences prefs, final boolean privacySensitive) {
        try {
            final CleanerConfig config = buildCleanerConfig(latinIME, prefs, privacySensitive);
            final EditorInfo editorInfo = latinIME.getCurrentInputEditorInfo();
            final String appPackage = editorInfo != null ? editorInfo.packageName : null;
            final String inputType = editorInfo != null ? String.valueOf(editorInfo.inputType) : null;
            if (privacySensitive) {
                return new VelaStreamingSession.CleanupSpec(config, null, null, appPackage,
                    inputType, null);
            }
            final boolean scribeEnabled = prefs.getBoolean(Settings.PREF_VELA_SCRIBE_ENABLED,
                helium314.keyboard.latin.settings.Defaults.PREF_VELA_SCRIBE_ENABLED);
            final boolean contextFallback = prefs.getBoolean(Settings.PREF_VELA_SCRIBE_CONTEXT_FALLBACK,
                helium314.keyboard.latin.settings.Defaults.PREF_VELA_SCRIBE_CONTEXT_FALLBACK);
            final InputConnection ic = latinIME.getCurrentInputConnection();
            final boolean wantsContext = scribeEnabled && contextFallback && ic != null;
            final String before = wantsContext ? safeText(ic.getTextBeforeCursor(256, 0)) : null;
            final String after = wantsContext ? safeText(ic.getTextAfterCursor(256, 0)) : null;
            return new VelaStreamingSession.CleanupSpec(config, before, after, appPackage,
                inputType, null);
        } catch (final Exception e) {
            Log.w(TAG, "could not build streaming cleanup spec", e);
            return null;
        }
    }

    private void startStreamingSession(final LatinIME latinIME,
            final StreamingPipeline.Builder builder, final String pipelineMode,
            final String transcriptionMode,
            final boolean privacySensitive,
            @Nullable final VelaStreamingSession.CleanupSpec cleanupSpec,
            @Nullable final VoiceRecordingPane voicePane,
            final boolean isYaps) {
        mIsVelaLoading = true;
        new Thread(() -> {
            try {
                if (velaTranscriber != null) {
                    velaTranscriber.release();
                    velaTranscriber = null;
                    mCachedVelaModelPath = null;
                    mCachedVelaLlmToggle = null;
                    mCachedVelaScribeToggle = null;
                    mCachedVelaLlmModelPath = null;
                    mCachedVelaLanguage = null;
                    mCachedVelaThreads = -1;
                    mCachedVelaCustomFillers = null;
                }
                final StreamingPipeline pipeline = builder.build();
                final VelaStreamingSession session = new VelaStreamingSession(latinIME, pipeline,
                    pipelineMode, privacySensitive, cleanupSpec,
                    buildStreamingListener(latinIME, voicePane, isYaps));
                session.start();

                latinIME.mHandler.post(() -> {
                    mIsVelaLoading = false;
                    if (mIsVelaRecordingCancelled) {
                        session.release();
                        return;
                    }
                    velaStreamingSession = session;
                    if (isYaps) {
                        startYapsTimer();
                    } else if (voicePane != null) {
                        voicePane.getStopCleanButton().setEnabled(true);
                        voicePane.getStopRawButton().setEnabled(true);
                        voicePane.getStatusText().setText("Recording...");
                        voicePane.resetDisplay();
                        startTimer(voicePane);
                    }
                });
            } catch (final Exception e) {
                latinIME.mHandler.post(() -> {
                    mIsVelaLoading = false;
                    showToast("Failed to start voice streaming: " + e.getMessage(), false);
                    if (isYaps) {
                        YapsUiManager.getInstance().stopActiveRecording();
                    } else {
                        showMainKeyboard(latinIME);
                    }
                });
            }
        }).start();
    }

    private VelaStreamingSession.Listener buildStreamingListener(final LatinIME latinIME,
            @Nullable final VoiceRecordingPane voicePane, final boolean isYaps) {
        return new VelaStreamingSession.Listener() {
            @Override
            public void onStreamingAmplitude(float normalized) {
                if (isYaps) {
                    YapsUiManager.getInstance().addAmplitude(normalized);
                } else if (voicePane != null && voicePane.getWaveformView() != null) {
                    voicePane.getWaveformView().addAmplitude(normalized);
                }
            }

            @Override
            public void onStreamingFinished(boolean cancelled) {
                stopTimer();
                if (velaStreamingSession != null) {
                    velaStreamingSession.release();
                    velaStreamingSession = null;
                }
                if (isYaps) {
                    YapsUiManager.getInstance().stopActiveRecording();
                } else {
                    showMainKeyboard(latinIME);
                }
            }

            @Override
            public void onStreamingError(String message) {
                showToast("Voice input error: " + message, false);
            }
        };
    }

    /** Shared VelaTranscriber builder (used by both Vela and Yaps modes) */
    private VelaTranscriber buildVelaTranscriber(final LatinIME latinIME,
            final SharedPreferences prefs, final String modelPath, final boolean useLlm,
            final String llmModelPath, final String language, final int threads,
            final java.util.List<String> customFillers, final boolean forceScribe,
            final boolean privacySensitive) throws Exception {
        VelaTranscriber.Builder builder = new VelaTranscriber.Builder(latinIME)
                .whisperModel(modelPath)
                .useLlmCleaner(useLlm && !privacySensitive, llmModelPath)
                .language(language)
                .threads(threads);

        // Scribe (AI Rewrite): read enable toggle, default style, per-app override
        final boolean scribeEnabled = !privacySensitive && prefs.getBoolean(Settings.PREF_VELA_SCRIBE_ENABLED,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_SCRIBE_ENABLED);
        final boolean effectiveScribe = !privacySensitive && (forceScribe || scribeEnabled);
        final String scribeStyle = resolveScribeStyle(latinIME, prefs, effectiveScribe);
        builder.scribe(effectiveScribe, scribeStyle, null);
        if (customFillers != null) {
            builder.customFillers(customFillers);
        }
        builder.personalDictionary(buildPersonalDictionary(prefs));
        builder.dictionaryKeywords(buildDictionaryKeywords(prefs));
        return builder.build();
    }

    /**
     * Resolve the Scribe style for the current editor: the global default style, overridden by a
     * per-app style when Scribe is enabled and the app has a non-"Default" override stored.
     */
    static String resolveScribeStyle(final LatinIME latinIME, final SharedPreferences prefs,
            final boolean scribeEnabled) {
        final String defaultStyle = prefs.getString(Settings.PREF_VELA_SCRIBE_STYLE,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_SCRIBE_STYLE);
        String resolvedStyle = defaultStyle;
        if (scribeEnabled) {
            final String appPackage = latinIME.getCurrentInputEditorInfo() != null
                ? latinIME.getCurrentInputEditorInfo().packageName : null;
            if (appPackage != null) {
                final String appStyle = prefs.getString(Settings.PREF_VELA_SCRIBE_APP_PREFIX + appPackage, null);
                if (appStyle != null && !appStyle.isEmpty() && !"Default".equals(appStyle)) {
                    resolvedStyle = appStyle;
                }
            }
        }
        return resolvedStyle;
    }

    static PersonalDictionary buildPersonalDictionary(final SharedPreferences prefs) {
        return new PersonalDictionary() {
            @Override
            public java.util.List<Pair<String, String>> getEntries() {
                java.util.List<Pair<String, String>> entries = new java.util.ArrayList<>();
                String json = prefs.getString(
                    Settings.PREF_VELA_DICT_PREFIX + "entries", "[]");
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(json);
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject obj = arr.getJSONObject(i);
                        String orig = obj.getString("original");
                        String repl = obj.getString("replacement");
                        if (!orig.isEmpty()) {
                            entries.add(new Pair<>(orig, repl));
                        }
                    }
                } catch (Exception ignored) {}
                return entries;
            }
        };
    }

    static DictionaryKeywords buildDictionaryKeywords(final SharedPreferences prefs) {
        return new DictionaryKeywords() {
            @Override
            public java.util.List<String> getKeywords() {
                java.util.List<String> keywords = new java.util.ArrayList<>();
                String json = prefs.getString(
                    Settings.PREF_VELA_DICT_PREFIX + "keywords", "[]");
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(json);
                    for (int i = 0; i < arr.length(); i++) {
                        String kw = arr.getString(i);
                        if (!kw.isEmpty()) {
                            keywords.add(kw);
                        }
                    }
                } catch (Exception ignored) {}
                return keywords;
            }
        };
    }

    static java.util.List<String> parseCustomFillers(final SharedPreferences prefs) {
        final String fillersStr = prefs.getString(Settings.PREF_VELA_CUSTOM_FILLERS,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_CUSTOM_FILLERS);
        return fillersStr.isEmpty() ? null : java.util.Arrays.asList(fillersStr.split("\\s*,\\s*"));
    }

    static String resolveLlmModelPath(final LatinIME latinIME, final SharedPreferences prefs) {
        String resolvedLlmPath = prefs.getString(Settings.PREF_VELA_LLM_MODEL_PATH,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_LLM_MODEL_PATH);
        if (resolvedLlmPath == null || resolvedLlmPath.isEmpty() || !new java.io.File(resolvedLlmPath).exists()) {
            String sharedPath = helium314.keyboard.settings.ModelDownloadHelper.getSharedModelPath(latinIME, "llm");
            if (sharedPath != null) {
                resolvedLlmPath = sharedPath;
            }
        }
        return resolvedLlmPath;
    }

    /**
     * Build the same cleanup configuration the instant path feeds into VelaTranscriber.Builder,
     * so the streaming path can run TextCleaner itself (StreamingPipeline bypasses it).
     * A privacy-sensitive editor never gets the LLM, so the ONNX model is not loaded at all.
     */
    static CleanerConfig buildCleanerConfig(final LatinIME latinIME, final SharedPreferences prefs,
            final boolean privacySensitive) {
        final boolean useLlm = !privacySensitive && prefs.getBoolean(Settings.PREF_VELA_LLM_TOGGLE,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_LLM_TOGGLE);
        final boolean scribeEnabled = !privacySensitive
            && prefs.getBoolean(Settings.PREF_VELA_SCRIBE_ENABLED,
                helium314.keyboard.latin.settings.Defaults.PREF_VELA_SCRIBE_ENABLED);
        return new CleanerConfig(
            useLlm,
            resolveLlmModelPath(latinIME, prefs),
            buildPersonalDictionary(prefs),
            parseCustomFillers(prefs),
            buildDictionaryKeywords(prefs),
            scribeEnabled,
            resolveScribeStyle(latinIME, prefs, scribeEnabled),
            null
        );
    }

    /**
     * Build the Scribe context snapshot from the current editor: surrounding text
     * (up to 256 chars), app package, and input type. Returns an empty ScribeInput
     * when Scribe is disabled or no InputConnection is available.
     *
     * Ticket 004: password/PII fields and IME_FLAG_NO_PERSONALIZED_LEARNING force
     * privacySensitive=true so the SDK skips Scribe and LLM cleanup entirely.
     * Ticket 006: the context-fallback toggle gates surrounding-text injection.
     */
    static ScribeInput buildScribeInput(final LatinIME latinIME, final SharedPreferences prefs) {
        return buildScribeInput(latinIME, prefs, false);
    }

    static ScribeInput buildScribeInput(final LatinIME latinIME, final SharedPreferences prefs,
            final boolean forceScribe) {
        boolean privacySensitive = true;
        EditorInfo editorInfo = null;
        try {
            if (latinIME == null) {
                return new ScribeInput(null, null, null, null, null, true);
            }
            editorInfo = latinIME.getCurrentInputEditorInfo();
            // Null editor during an ACTIVE session fails closed (map #72, ticket #80);
            // pre-session keeps the legacy null => not sensitive semantics.
            privacySensitive = com.velavoice.sdk.PrivacyGuard.isPrivacySensitiveEditor(
                    editorInfo, KeyboardSwitcher.getInstance().isVelaSessionActive());
        } catch (Exception e) {
            privacySensitive = true;
        }

        if (privacySensitive) {
            return new ScribeInput(null, null, null, null, null, true);
        }

        final boolean scribeEnabled = prefs != null && prefs.getBoolean(Settings.PREF_VELA_SCRIBE_ENABLED,
            helium314.keyboard.latin.settings.Defaults.PREF_VELA_SCRIBE_ENABLED);
        if (!scribeEnabled && !forceScribe) {
            return new ScribeInput(null, null, null, null, null, false);
        }

        try {
            final boolean contextFallback = prefs != null && prefs.getBoolean(Settings.PREF_VELA_SCRIBE_CONTEXT_FALLBACK,
                helium314.keyboard.latin.settings.Defaults.PREF_VELA_SCRIBE_CONTEXT_FALLBACK);
            final InputConnection ic = latinIME != null ? latinIME.getCurrentInputConnection() : null;
            final String before = contextFallback && ic != null
                ? safeText(ic.getTextBeforeCursor(256, 0)) : null;
            final String after = contextFallback && ic != null
                ? safeText(ic.getTextAfterCursor(256, 0)) : null;
            final String appPackage = editorInfo != null ? editorInfo.packageName : null;
            final String inputType = editorInfo != null ? String.valueOf(editorInfo.inputType) : null;
            return new ScribeInput(
                before,
                after,
                appPackage,
                inputType,
                null, //overrideStyle resolved per-app inside buildVelaTranscriber
                false
            );
        } catch (Exception e) {
            return new ScribeInput(null, null, null, null, null, true);
        }
    }

    /** Ticket 004 safeguard: password/PII fields must never reach the LLM.
     *  Delegates to the shared detector so classification cannot drift between apps. */
    static boolean isPrivacySensitiveEditor(final EditorInfo editorInfo) {
        return com.velavoice.sdk.PrivacyGuard.isPrivacySensitiveEditor(editorInfo);
    }

    private static String safeText(final CharSequence cs) {
        return cs != null ? cs.toString() : null;
    }

    /** Build VelaRecordingCallback — when isYaps=true, use YapsUiManager for UI; when false, use voicePane */
    VelaRecordingCallback buildVelaCallbacks(final LatinIME latinIME,
            @Nullable final VoiceRecordingPane voicePane, final boolean isYaps) {
        return buildVelaCallbacks(latinIME, voicePane, isYaps, false);
    }

    VelaRecordingCallback buildVelaCallbacks(final LatinIME latinIME,
            @Nullable final VoiceRecordingPane voicePane, final boolean isYaps,
            final boolean privacySensitive) {
        return new VelaRecordingCallback() {
            @Override
            public void onAmplitude(float normalized) {
                if (mIsVelaRecordingCancelled) return;
                latinIME.mHandler.post(() -> {
                    if (isYaps) {
                        YapsUiManager.getInstance().addAmplitude(normalized);
                    } else if (voicePane != null && voicePane.getWaveformView() != null) {
                        voicePane.getWaveformView().addAmplitude(normalized);
                    }
                });
            }

            @Override
            public void onResult(TranscriptionResult result) {
                stopTimer();
                if (mIsVelaRecordingCancelled) {
                    return;
                }
                if (!privacySensitive) {
                    TranscriptionStorage.save(
                        latinIME,
                        result.getRawTranscript(),
                        result.getCleanedTranscript(),
                        result.getDurationMs(),
                        result.getAudioBytes(),
                        privacySensitive
                    );
                }
                latinIME.mHandler.post(() -> {
                    if (!mIsVelaRecordingCancelled) {
                        latinIME.onTextInput(result.getCleanedTranscript());
                    }
                    if (isYaps) {
                        // YAPS mode: stopActiveRecording restores the keyboard UI by
                        // removing the dim overlay, toolbar, and restoring suggestion strip.
                        // We do NOT call showMainKeyboard here because the input view was
                        // never replaced, and calling setInputView would trigger a full
                        // re-layout that destabilizes onComputeInsets.
                        YapsUiManager.getInstance().stopActiveRecording();
                    } else {
                        showMainKeyboard(latinIME);
                    }
                });
            }

            @Override
            public void onError(VelaException e) {
                stopTimer();
                if (mIsVelaRecordingCancelled) {
                    return;
                }
                latinIME.mHandler.post(() -> {
                    showToast("Voice input error: " + e.getMessage(), false);
                    if (isYaps) {
                        YapsUiManager.getInstance().stopActiveRecording();
                    } else {
                        showMainKeyboard(latinIME);
                    }
                });
            }
        };
    }

    public void showMainKeyboard(final LatinIME latinIME) {
        if (mSavedInputView != null) {
            // Restore the original input view that was saved before showing the voice pane.
            // This avoids creating a brand-new input view that would lack a keyboard layout
            // (setKeyboard() would not have been called on its MainKeyboardView, causing a blank screen).
            latinIME.setInputView(mSavedInputView);
            mSavedInputView = null;
        } else {
            View mainKeyboard = onCreateInputView(latinIME, mIsHardwareAcceleratedDrawingEnabled);
            latinIME.setInputView(mainKeyboard);
        }
    }

    public void onHideWindow() {
        if (mKeyboardView != null) {
            mKeyboardView.onHideWindow();
        }
    }

    private void setKeyboard(final KeyboardElement keyboardElement, @NonNull final KeyboardSwitchState toggleState) {
        // with a hardware keyboard we might get here without ever calling onCreateInputView, so don't crash
        if (mKeyboardView == null) return;

        // Make {@link MainKeyboardView} visible and hide {@link EmojiPalettesView}.
        final SettingsValues currentSettingsValues = Settings.getValues();
        setMainKeyboardFrame(currentSettingsValues, toggleState);
        // TODO: pass this object to setKeyboard instead of getting the current values.
        final MainKeyboardView keyboardView = mKeyboardView;
        final Keyboard oldKeyboard = keyboardView.getKeyboard();
        final Keyboard newKeyboard = mKeyboardLayoutSet.getKeyboard(keyboardElement);
        keyboardView.setKeyboard(newKeyboard);
        mCurrentInputView.setKeyboardTopPadding(newKeyboard.mTopPadding);
        keyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn);
        keyboardView.updateShortcutKey(mRichImm.isShortcutImeReady());
        final boolean subtypeChanged = (oldKeyboard == null) || !newKeyboard.mId.getSubtype().equals(oldKeyboard.mId.getSubtype());
        final int languageOnSpacebarFormatType = LanguageOnSpacebarUtils.getLanguageOnSpacebarFormatType(newKeyboard.mId.getSubtype());
        final boolean hasMultipleEnabledIMEsOrSubtypes = mRichImm.hasMultipleEnabledIMEsOrSubtypes(true);
        keyboardView.startDisplayLanguageOnSpacebar(subtypeChanged, languageOnSpacebarFormatType, hasMultipleEnabledIMEsOrSubtypes);

        if (currentSettingsValues.needsToLookupSuggestions()
                                    && (currentSettingsValues.mInlineEmojiSearch || currentSettingsValues.mSuggestEmojis)) {
            EmojiParserKt.loadEmojiDefaultVersionsAndPopupSpecs(mThemeContext);
        }
    }

    @Nullable public Keyboard getKeyboard() {
        if (mKeyboardView != null) {
            return mKeyboardView.getKeyboard();
        }
        return null;
    }

    // TODO: Remove this method. Come up with a more comprehensive way to reset the keyboard layout
    // when a keyboard layout set doesn't get reloaded in LatinIME.onStartInputViewInternal().
    public void resetKeyboardStateToAlphabet(final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onResetKeyboardStateToAlphabet(currentAutoCapsState, currentRecapitalizeState);
    }

    public void onPressKey(int code, int pointerCount, int currentAutoCapsState,
            @Nullable RecapitalizeMode currentRecapitalizeState) {
        mState.onPressKey(code, pointerCount, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onReleaseKey(final int code, final boolean withSliding,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onReleaseKey(code, withSliding, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onFinishSlidingInput(final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onFinishSlidingInput(currentAutoCapsState, currentRecapitalizeState);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetKeyboard(@NonNull ShiftMode shiftMode) {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetKeyboard");
        }
        setKeyboard(shiftMode.element, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setSymbolsKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsKeyboard");
        }
        setKeyboard(KeyboardElement.SYMBOLS, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setSymbolsShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsShiftedKeyboard");
        }
        setKeyboard(KeyboardElement.SYMBOLS_SHIFTED, KeyboardSwitchState.SYMBOLS_SHIFTED);
    }

    public boolean isImeSuppressedByHardwareKeyboard(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        return settingsValues.mHasHardwareKeyboard && toggleState == KeyboardSwitchState.HIDDEN;
    }

    private void setMainKeyboardFrame(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        final int visibility = isImeSuppressedByHardwareKeyboard(settingsValues, toggleState) ? View.GONE : View.VISIBLE;
        final int stripVisibility = mLatinIME.hasSuggestionStripView()? View.VISIBLE : View.GONE;
        mStripContainer.setVisibility(stripVisibility);
        PointerTracker.switchTo(mKeyboardView);
        mKeyboardView.setVisibility(visibility);
        // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        mMainKeyboardFrame.setVisibility(visibility);
        mKeyboardViewWrapper.setVisibility(Settings.getInstance().readShowToolbarOnly() ? View.GONE : View.VISIBLE);
        mEmojiPalettesView.setVisibility(View.GONE);
        mEmojiPalettesView.stopEmojiPalettes();
        mEmojiTabStripView.setVisibility(View.GONE);
        mClipboardStripScrollView.setVisibility(View.GONE);
        mSuggestionStripView.setVisibility(stripVisibility);
        mClipboardHistoryView.setVisibility(View.GONE);
        mClipboardHistoryView.stopClipboardHistory();
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setEmojiKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setEmojiKeyboard");
        }
        mMainKeyboardFrame.setVisibility(View.VISIBLE);
        // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        mKeyboardView.setVisibility(View.GONE);
        mSuggestionStripView.setVisibility(View.GONE);
        mStripContainer.setVisibility(getSecondaryStripVisibility());
        mClipboardStripScrollView.setVisibility(View.GONE);
        mEmojiTabStripView.setVisibility(View.VISIBLE);
        mClipboardHistoryView.setVisibility(View.GONE);
        mEmojiPalettesView.startEmojiPalettes(mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
        mEmojiPalettesView.setVisibility(View.VISIBLE);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setClipboardKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setClipboardKeyboard");
        }
        mMainKeyboardFrame.setVisibility(View.VISIBLE);
        // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        mKeyboardView.setVisibility(View.GONE);
        mEmojiTabStripView.setVisibility(View.GONE);
        mSuggestionStripView.setVisibility(View.GONE);
        mStripContainer.setVisibility(getSecondaryStripVisibility());
        mClipboardStripScrollView.post(() -> mClipboardStripScrollView.fullScroll(HorizontalScrollView.FOCUS_RIGHT));
        mClipboardStripScrollView.setVisibility(View.VISIBLE);
        mEmojiPalettesView.setVisibility(View.GONE);
        mClipboardHistoryView.startClipboardHistory(mLatinIME.getClipboardHistoryManager(), mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
        mClipboardHistoryView.setVisibility(View.VISIBLE);
    }

    @Override
    public void setNumpadKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setNumpadKeyboard");
        }
        setKeyboard(KeyboardElement.NUMPAD, KeyboardSwitchState.OTHER);
    }

    @Override
    public void setDpadKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setDpadKeyboard");
        }
        setKeyboard(KeyboardElement.DPAD, KeyboardSwitchState.OTHER);
    }

    @Override
    public void toggleLayout(@NonNull LayoutDirective.Utility layout, int autoCapsFlags, @Nullable RecapitalizeMode recapitalizeMode) {
        mState.toggleLayout(layout, autoCapsFlags, recapitalizeMode);
    }

    @Override
    public void onLongPressAlphaSymbolForNumpad() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "onLongPressAlphaSymbol");
        }
        mState.onLongPressAlphaSymbolForNumpad();
    }

    public enum KeyboardSwitchState {
        HIDDEN(null),
        SYMBOLS_SHIFTED(KeyboardElement.SYMBOLS_SHIFTED),
        EMOJI(KeyboardElement.EMOJI_RECENTS),
        CLIPBOARD(KeyboardElement.CLIPBOARD),
        OTHER(null);

        final KeyboardElement mKeyboardElement;

        KeyboardSwitchState(KeyboardElement keyboardElement) {
            mKeyboardElement = keyboardElement;
        }
    }

    public KeyboardSwitchState getKeyboardSwitchState() {
        boolean hidden = !isShowingEmojiPalettes() && !isShowingClipboardHistory()
                && (mKeyboardLayoutSet == null
                || mKeyboardView == null
                || !mKeyboardView.isShown());
        if (hidden) {
            return KeyboardSwitchState.HIDDEN;
        } else if (isShowingEmojiPalettes()) {
            return KeyboardSwitchState.EMOJI;
        } else if (isShowingClipboardHistory()) {
            return KeyboardSwitchState.CLIPBOARD;
        } else if (isShowingKeyboardId(KeyboardElement.SYMBOLS_SHIFTED)) {
            return KeyboardSwitchState.SYMBOLS_SHIFTED;
        }
        return KeyboardSwitchState.OTHER;
    }

    public void onToggleKeyboard(@NonNull final KeyboardSwitchState toggleState) {
        KeyboardSwitchState currentState = getKeyboardSwitchState();
        Log.w(TAG, "onToggleKeyboard() : Current = " + currentState + " : Toggle = " + toggleState);
        if (currentState == toggleState) {
            mLatinIME.stopShowingInputView();
            mLatinIME.hideWindow();
            setAlphabetKeyboard(ShiftMode.UNSHIFT);
        } else {
            mLatinIME.startShowingInputView(true);
            if (toggleState == KeyboardSwitchState.EMOJI) {
                setEmojiKeyboard();
            } else if (toggleState == KeyboardSwitchState.CLIPBOARD) {
                setClipboardKeyboard();
            } else {
                mEmojiPalettesView.stopEmojiPalettes();
                mEmojiPalettesView.setVisibility(View.GONE);

                mClipboardHistoryView.stopClipboardHistory();
                mClipboardHistoryView.setVisibility(View.GONE);

                mMainKeyboardFrame.setVisibility(View.VISIBLE);
                mKeyboardView.setVisibility(View.VISIBLE);
                // todo: this doesn't tell KeyboardState that this mode has been set.
                //  example: if you press physical alt the more symbols keyboard will appear,
                //  but if you then do 2 D-pad space swipes it'll return to alpha instead
                //  because KeyboardState thinks the `mode` is alphabet when doing the
                //  initial toggle.
                setKeyboard(toggleState.mKeyboardElement, toggleState);
            }
        }
    }

    // Future method for requesting an updating to the shift state.
    @Override
    public void requestUpdatingShiftState(final int autoCapsFlags, @Nullable final RecapitalizeMode recapitalizeMode) {
        if (DEBUG_ACTION) {
            Log.d(TAG, "requestUpdatingShiftState: "
                    + " autoCapsFlags=" + CapsModeUtils.flagsToString(autoCapsFlags)
                    + " recapitalizeMode=" + recapitalizeMode);
        }
        mState.onUpdateShiftState(autoCapsFlags, recapitalizeMode);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void startDoubleTapShiftKeyTimer() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "startDoubleTapShiftKeyTimer");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        if (keyboardView != null) {
            keyboardView.startDoubleTapShiftKeyTimer();
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void cancelDoubleTapShiftKeyTimer() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "cancelDoubleTapShiftKeyTimer");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        if (keyboardView != null) {
            keyboardView.cancelDoubleTapShiftKeyTimer();
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setOneHandedModeEnabled(boolean enabled) {
        setOneHandedModeEnabled(enabled, false);
    }

    public void setOneHandedModeEnabled(boolean enabled, boolean force) {
        if (!force && mKeyboardViewWrapper.getOneHandedModeEnabled() == enabled) {
            return;
        }
        final Settings settings = Settings.getInstance();
        mKeyboardViewWrapper.setOneHandedModeEnabled(enabled);
        mKeyboardViewWrapper.setOneHandedGravity(settings.getCurrent().mOneHandedModeGravity);

        // oneHandeMode is always disabled when floating, and we shouldn't mess up the setting
        if (enabled != settings.getCurrent().mOneHandedModeEnabled)
            settings.writeOneHandedModeEnabled(enabled);
        reloadKeyboard();
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void switchOneHandedMode() {
        mKeyboardViewWrapper.switchOneHandedModeSide();
        Settings.getInstance().writeOneHandedModeGravity(mKeyboardViewWrapper.getOneHandedGravity());
    }

    @Override
    public void setFloatingKeyboardEnabled(boolean enabled) {
        if (enabled != Settings.getValues().mIsFloatingKeyboard)
            // mIsFloatingKeyboard is always disabled when device is locked, and we shouldn't mess up the setting
            SettingsKt.setFloatingKeyboardEnabled(mThemeContext, enabled);
        if (enabled) FloatingKeyboardUtils.setFloating(mCurrentInputView);
        else FloatingKeyboardUtils.disableFloating(mCurrentInputView);
        setBackgroundGatheringIndicatorPosition();
    }

    public void toggleSplitKeyboardMode() {
        final Settings settings = Settings.getInstance();
        settings.writeSplitKeyboardEnabled(
            !settings.getCurrent().mIsSplitKeyboardEnabled,
            mCurrentOrientation == Configuration.ORIENTATION_LANDSCAPE,
            FoldableUtils.INSTANCE.isFolded()
        );
        setOneHandedModeEnabled(settings.getCurrent().mOneHandedModeEnabled, true);
        reloadKeyboard();
    }

    public void reloadKeyboard() {
        if (mCurrentInputView == null)
            return;
        mEmojiPalettesView.clearKeyboardCache();
        reloadMainKeyboard();
    }

    public void reloadMainKeyboard() {
        // Reload the entire keyboard, and switch to the previous layout
        final boolean wasEmoji = isShowingEmojiPalettes();
        final boolean wasClipboard = isShowingClipboardHistory();
        loadKeyboard(mLatinIME.getCurrentInputEditorInfo(), Settings.getValues(),
                mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState(), null);
        if (wasEmoji) {
            setEmojiKeyboard();
        } else if (wasClipboard) {
            setClipboardKeyboard();
        }
    }

    /**
     * Displays a toast message.
     *
     * @param text The text to display in the toast message.
     * @param briefToast If true, the toast duration will be short; otherwise, it will last longer.
     */
    public void showToast(final String text, final boolean briefToast){
        // In API 32 and below, toasts can be shown without a notification permission.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            final int toastLength = briefToast ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG;
            final Toast toast = Toast.makeText(mLatinIME, text, toastLength);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        } else {
            final int toastLength = briefToast ? 2000 : 3500;
            showFakeToast(text, toastLength);
        }
    }

    private static int getSecondaryStripVisibility() {
        return Settings.getValues().isSecondaryStripVisible()? View.VISIBLE : View.GONE;
    }

    // Displays a toast-like message with the provided text for a specified duration.
    private void showFakeToast(final String text, final int timeMillis) {
        if (mFakeToastView.getVisibility() == View.VISIBLE) return;

        final Drawable appIcon = mFakeToastView.getCompoundDrawables()[0];
        if (appIcon != null) {
            final int bound = mFakeToastView.getLineHeight();
            appIcon.setBounds(0, 0, bound, bound);
            mFakeToastView.setCompoundDrawables(appIcon, null, null, null);
        }
        mFakeToastView.setText(text);
        mFakeToastView.setVisibility(View.VISIBLE);
        mFakeToastView.bringToFront();
        mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_in));

        mFakeToastView.postDelayed(() -> {
            mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_out));
            mFakeToastView.setVisibility(View.GONE);
        }, timeMillis);
    }

    public void setBackgroundGatheringIndicator(boolean enabled, boolean hasData, boolean saving) {
        if (mCurrentInputView == null) return;
        mBackgroundGatheringIndicator.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) return;
        mBackgroundGatheringIndicator.setImageResource(hasData ? R.drawable.btn_keyboard_key_action_normal_lxx_base : R.drawable.ring);
        setBackgroundGatheringIndicatorPosition();
        if (!saving) return;
        mBackgroundGatheringIndicator.setImageTintList(ColorStateList.valueOf(0xff00a000));
        mBackgroundGatheringIndicator.postDelayed(() -> mBackgroundGatheringIndicator.setImageTintList(ColorStateList.valueOf(0xffa00000)), 1500);
    }

    private void setBackgroundGatheringIndicatorPosition() {
        if (mBackgroundGatheringIndicator == null || mBackgroundGatheringIndicator.getVisibility() != View.VISIBLE) return;
        if (mBackgroundGatheringIndicator.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margin) {
            Keyboard kb = mKeyboardView.getKeyboard();
            if (kb != null)
                margin.topMargin = kb.mOccupiedHeight - KtxKt.dpToPx(16, mCurrentInputView.getResources());
            mBackgroundGatheringIndicator.setLayoutParams(mBackgroundGatheringIndicator.getLayoutParams());
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public boolean popDoubleTapShiftKeyTimer() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "isInDoubleTapShiftKeyTimeout");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        return keyboardView != null && keyboardView.popDoubleTapShiftKeyTimer();
    }

    /**
     * Updates state machine to figure out when to automatically switch back to the previous mode.
     */
    public void onEvent(final Event event, final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onEvent(event, currentAutoCapsState, currentRecapitalizeState);
    }

    public boolean isShowingKeyboardId(@NonNull KeyboardElement... keyboardElements) {
        if (mKeyboardView == null || !mKeyboardView.isShown()) {
            return false;
        }
        final Keyboard keyboard = mKeyboardView.getKeyboard();
        if (keyboard == null) // may happen when using hardware keyboard
            return false;
        KeyboardElement activeKeyboardId = keyboard.mId.getElement();
        for (KeyboardElement keyboardElement : keyboardElements) {
            if (activeKeyboardId == keyboardElement) {
                return true;
            }
        }
        return false;
    }

    public boolean isShowingEmojiPalettes() {
        return mEmojiPalettesView != null && mEmojiPalettesView.isShown();
    }

    public boolean isShowingClipboardHistory() {
        return mClipboardHistoryView != null && mClipboardHistoryView.isShown();
    }

    public boolean isShowingPopupKeysPanel() {
        if (isShowingEmojiPalettes() || isShowingClipboardHistory()) {
            return false;
        }
        return mKeyboardView.isShowingPopupKeysPanel();
    }

    public boolean isShowingStripContainer() {
        return mStripContainer.isShown();
    }

    public EmojiPalettesView getEmojiPalettesView() {
        return mEmojiPalettesView;
    }

    public View getVisibleKeyboardView() {
        if (isShowingEmojiPalettes()) {
            return mEmojiPalettesView;
        } else if (isShowingClipboardHistory()) {
            return mClipboardHistoryView;
        }
        return mKeyboardView;
    }

    public View getWrapperView() {
        return mKeyboardViewWrapper;
    }

    public View getEmojiTabStrip() {
        return mEmojiTabStripView;
    }

    public LinearLayout getClipboardStrip() {
        return mClipboardStripView;
    }

    public MainKeyboardView getMainKeyboardView() {
        return mKeyboardView;
    }

    public FrameLayout getStripContainer() { return mStripContainer; }

    public void deallocateMemory() {
        if (mKeyboardView != null) {
            mKeyboardView.cancelAllOngoingEvents();
            mKeyboardView.deallocateMemory();
        }
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.stopEmojiPalettes();
        }
        if (mClipboardHistoryView != null) {
            mClipboardHistoryView.stopClipboardHistory();
        }
    }

    public void trimMemory() {
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.clearKeyboardCache();
        }
    }

    @SuppressLint("InflateParams")
    public View onCreateInputView(@NonNull Context displayContext, boolean isHardwareAcceleratedDrawingEnabled) {
        Log.d(TAG, "create new input view");
        if (mKeyboardView != null) {
            mKeyboardView.closing();
        }
        PointerTracker.clearOldViewData();
        SharedPreferences prefs = KtxKt.prefs(displayContext);
        if (mSuggestionStripView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mSuggestionStripView);
        if (mClipboardHistoryView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mClipboardHistoryView);
        if (mThemeNeedsReload) // necessary in some cases (e.g. theme switch) when mThemeNeedsReload is set before first keyboard load
            Settings.getInstance().loadSettings(displayContext, Settings.getValues().mLocale, Settings.getValues().mInputAttributes);

        updateKeyboardThemeAndContextThemeWrapper(displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        mCurrentInputView = (InputView)LayoutInflater.from(mThemeContext).inflate(R.layout.input_view, null);
        mMainKeyboardFrame = mCurrentInputView.findViewById(R.id.main_keyboard_frame);
        mEmojiPalettesView = mCurrentInputView.findViewById(R.id.emoji_palettes_view);
        mClipboardHistoryView = mCurrentInputView.findViewById(R.id.clipboard_history_view);
        mFakeToastView = mCurrentInputView.findViewById(R.id.fakeToast);

        mKeyboardViewWrapper = mCurrentInputView.findViewById(R.id.keyboard_view_wrapper);
        mKeyboardViewWrapper.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mKeyboardView = mCurrentInputView.findViewById(R.id.keyboard_view);
        mKeyboardView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mKeyboardView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mEmojiPalettesView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mEmojiPalettesView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mClipboardHistoryView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mClipboardHistoryView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mEmojiTabStripView = mCurrentInputView.findViewById(R.id.emoji_tab_strip);
        mClipboardStripView = mCurrentInputView.findViewById(R.id.clipboard_strip);
        mClipboardStripScrollView = mCurrentInputView.findViewById(R.id.clipboard_strip_scroll_view);
        mSuggestionStripView = mCurrentInputView.findViewById(R.id.suggestion_strip_view);
        mStripContainer = mCurrentInputView.findViewById(R.id.strip_container);
        mBackgroundGatheringIndicator = mCurrentInputView.findViewById(R.id.backgroundGatheringIndicator);

        prefs.registerOnSharedPreferenceChangeListener(mSuggestionStripView);
        prefs.registerOnSharedPreferenceChangeListener(mClipboardHistoryView);
        PointerTracker.switchTo(mKeyboardView);

        YapsUiManager.getInstance().init(mLatinIME, this, mSuggestionStripView, mKeyboardViewWrapper);
        YapsUiManager.getInstance().setupIdleState();

        return mCurrentInputView;
    }

    public CapsMode getKeyboardCapsMode() {
        Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return CapsMode.OFF;
        }
        return keyboard.mId.getElement().getCapsMode();
    }

    public String getCurrentKeyboardScript() {
        if (null == mKeyboardLayoutSet) {
            return ScriptUtils.SCRIPT_UNKNOWN;
        }
        return mKeyboardLayoutSet.getScript();
    }

    public void switchToSubtype(InputMethodSubtype subtype) {
        mLatinIME.switchToSubtype(subtype);
    }

    // used for debug
    public String getLocaleAndConfidenceInfo() {
        return mLatinIME.getLocaleAndConfidenceInfo();
    }

    /** Marks the theme as outdated. The theme will be reloaded next time the keyboard is shown.
     *  If the keyboard is currently showing, theme will be reloaded immediately. */
    public void setThemeNeedsReload() {
        mThemeNeedsReload = true;
        if (mLatinIME == null || !mLatinIME.isInputViewShown())
            return; // will be reloaded right before showing IME

        // Hide and show IME, showing will trigger the reload.
        // Reloading while IME is shown is glitchy, and hiding / showing is so fast the user shouldn't notice.
        mLatinIME.hideWindow();
        try {
            mLatinIME.showWindow(true);
        } catch (IllegalStateException e) {
            // in tests isInputViewShown returns true, but showWindow throws "IllegalStateException: Window token is not set yet."
        }
    }
}
