package com.velavoice.sdk.cleaner

/**
 * Interface for providing dictionary keywords — words/phrases the user wants
 * the transcription engine to recognize accurately.
 *
 * Unlike [PersonalDictionary] which replaces words, dictionary keywords are
 * vocabulary hints that should be preserved during cleaning and can be used
 * as initial prompt context for the Whisper model to improve recognition.
 *
 * Analogous to Willow Voice's "Dictionary Terms" feature.
 */
interface DictionaryKeywords {
    /** Return list of keyword strings the user has defined. */
    fun getKeywords(): List<String>
}
