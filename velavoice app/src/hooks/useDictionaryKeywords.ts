/**
 * Module: src/hooks/useDictionaryKeywords
 * Intent: Personal dictionary + protected-keyword CRUD state and handlers.
 * Responsibilities: Own dictionary/keywords state, ModelManager loads, add/delete handlers.
 * Public API: useDictionaryKeywords() -> state values, input setters, loaders, handlers.
 * Invariants: All persistence goes through ModelManager; alert() feedback preserved.
 * Side Effects: SQLite via ModelManager; alert() on validation/persistence errors.
 */
import { useCallback, useState } from 'react';
import { ModelManager, DictionaryEntry, DictionaryKeyword } from '../services/ModelManager';

export function useDictionaryKeywords() {
  const [dictionary, setDictionary] = useState<DictionaryEntry[]>([]);
  const [originalWord, setOriginalWord] = useState('');
  const [replacement, setReplacement] = useState('');
  const [language, setLanguage] = useState('');
  const [priority, setPriority] = useState('1');

  const [keywords, setKeywords] = useState<DictionaryKeyword[]>([]);
  const [keywordInput, setKeywordInput] = useState('');

  const loadDictionary = useCallback(async () => {
    try {
      const list = await ModelManager.getDictionaryEntries();
      setDictionary(list);
    } catch (e) {
      console.error('Failed to load dictionary', e);
    }
  }, []);

  const loadKeywords = useCallback(async () => {
    try {
      const list = await ModelManager.getKeywords();
      setKeywords(list);
    } catch (e) {
      console.error('Failed to load keywords', e);
    }
  }, []);

  const handleAddEntry = useCallback(async () => {
    if (!originalWord.trim() || !replacement.trim()) {
      alert('Please fill out both the original word and its replacement.');
      return;
    }

    try {
      await ModelManager.addDictionaryEntry(
        originalWord.trim(),
        replacement.trim(),
        language.trim() || null,
        parseInt(priority, 10) || 1
      );
      setOriginalWord('');
      setReplacement('');
      setLanguage('');
      setPriority('1');
      await loadDictionary();
    } catch (e) {
      console.error('Failed to add dictionary entry', e);
      alert('Failed to add entry. Word might already exist.');
    }
  }, [originalWord, replacement, language, priority, loadDictionary]);

  const handleDeleteEntry = useCallback(async (id?: number) => {
    if (id === undefined) return;
    try {
      await ModelManager.deleteDictionaryEntry(id);
      await loadDictionary();
    } catch (e) {
      console.error('Failed to delete dictionary entry', e);
      alert('Failed to delete entry.');
    }
  }, [loadDictionary]);

  const handleAddKeyword = useCallback(async () => {
    const trimmed = keywordInput.trim();
    if (!trimmed) {
      alert('Please enter a keyword.');
      return;
    }
    // Validate that keyword contains only valid word characters
    if (!/^[\w\s-]+$/.test(trimmed)) {
      alert('Keywords can only contain letters, numbers, spaces, and hyphens.');
      return;
    }
    try {
      await ModelManager.addKeyword(trimmed, null);
      setKeywordInput('');
      await loadKeywords();
    } catch (e) {
      console.error('Failed to add keyword', e);
      alert('Failed to add keyword. It might already exist.');
    }
  }, [keywordInput, loadKeywords]);

  const handleDeleteKeyword = useCallback(async (id?: number) => {
    if (id === undefined) return;
    try {
      await ModelManager.deleteKeyword(id);
      await loadKeywords();
    } catch (e) {
      console.error('Failed to delete keyword', e);
      alert('Failed to delete keyword.');
    }
  }, [loadKeywords]);

  return {
    dictionary,
    originalWord,
    setOriginalWord,
    replacement,
    setReplacement,
    keywords,
    keywordInput,
    setKeywordInput,
    loadDictionary,
    loadKeywords,
    handleAddEntry,
    handleDeleteEntry,
    handleAddKeyword,
    handleDeleteKeyword,
  };
}
