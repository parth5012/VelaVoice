import type { DictionaryEntry, DictionaryKeyword } from '../services/ModelManager';

// Pure dictionary-clean step behind the Studio sandbox in App.tsx.
//
// Applies personal-dictionary replacements (priority-ordered) and reports
// which protected keywords were found, so the sandbox preview is testable
// without component state.

export function cleanWithDictionary(
  testText: string,
  dictionary: DictionaryEntry[],
  keywords: DictionaryKeyword[]
): string {
  let cleaned = testText;

  // Step 1: Apply dictionary replacements (corrections)
  const sortedDict = [...dictionary].sort((a, b) => (b.priority || 0) - (a.priority || 0));
  for (const entry of sortedDict) {
    if (entry.original_word) {
      const regex = new RegExp(`\\b${entry.original_word}\\b`, 'gi');
      cleaned = cleaned.replace(regex, entry.replacement);
    }
  }

  // Step 2: Show which keywords are protected from filler removal
  const keywordSet = new Set(keywords.map(k => k.keyword.toLowerCase()));
  const protectedFound: string[] = [];
  const testWords = testText.toLowerCase().split(/\b/);
  for (const word of testWords) {
    const trimmed = word.trim();
    if (trimmed && keywordSet.has(trimmed)) {
      protectedFound.push(trimmed);
    }
  }
  if (protectedFound.length > 0) {
    cleaned += `\n\n[Protected keywords in text: ${[...new Set(protectedFound)].join(', ')}]`;
  }

  return cleaned;
}
