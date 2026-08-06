package com.velavoice.sdk.cleaner

interface PersonalDictionary {
    /** Return word replacement pairs. Applied in order, case-insensitive. */
    fun getEntries(): List<Pair<String, String>>
}
