import Constants from 'expo-constants';
import { getGeminiApiKey, getGeminiModel, GEMINI_MODEL } from './GeminiService';

const GEMINI_API_KEY = Constants.expoConfig?.extra?.geminiApiKey || process.env.GEMINI_API_KEY;
const GROQ_API_KEY = Constants.expoConfig?.extra?.groqApiKey || process.env.GROQ_API_KEY;

interface ScribeRewriteOptions {
  model: 'gemini' | 'groq';
  text: string;
  style: string;
  customPrompt?: string;
}

interface ScribeRewriteResult {
  success: boolean;
  text?: string;
  error?: string;
}

export class ScribeAI {
  static async rewrite(options: ScribeRewriteOptions): Promise<ScribeRewriteResult> {
    const { model, text, style, customPrompt } = options;

    const stylePrompts: Record<string, string> = {
      'Professional': 'Rewrite this transcription in professional, polished language. Use proper grammar, complete sentences, and formal tone.',
      'Casual': 'Rewrite this transcription in casual, conversational language. Make it sound natural and relaxed like spoken dialogue.',
      'Bullet Points': 'Convert this transcription into clear bullet points. Extract key information and present it as a structured list.',
      'Email Draft': 'Rewrite this transcription as a professional email draft. Include appropriate greeting and closing.',
      'Proofread': 'Proofread and correct this transcription. Fix grammar, spelling, and punctuation errors while preserving the original meaning.',
      'Custom': 'Rewrite this transcription improving clarity and readability while maintaining the original meaning and tone.',
    };

    const basePrompt = customPrompt || stylePrompts[style] || stylePrompts['Professional'];
    const prompt = basePrompt + '\n\nOriginal text:\n' + text + '\n\nRewritten text:';

    try {
      if (model === 'gemini') {
        return await this.rewriteWithGemini(prompt);
      } else if (model === 'groq') {
        return await this.rewriteWithGroq(prompt);
      }
    } catch (error: any) {
      return { success: false, error: error.message || 'Rewrite failed' };
    }

    return { success: false, error: 'Invalid model specified' };
  }

  static async rewriteWithGemini(prompt: string): Promise<ScribeRewriteResult> {
    const apiKey = (await getGeminiApiKey().catch(() => null)) || GEMINI_API_KEY;
    if (!apiKey) {
      return { success: false, error: 'Gemini API key not configured. Please configure your key in Engine Settings.' };
    }

    const geminiModel = (await getGeminiModel().catch(() => null)) || GEMINI_MODEL;
    const response = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${geminiModel}:generateContent?key=${apiKey}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          contents: [{ parts: [{ text: prompt }] }],
          generationConfig: { temperature: 0.3, maxOutputTokens: 1024 },
        }),
      }
    );

    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      return { success: false, error: error?.error?.message || 'Gemini API error' };
    }

    const data = await response.json();
    const text = data?.candidates?.[0]?.content?.parts?.[0]?.text?.trim();

    if (!text) {
      return { success: false, error: 'No text returned from Gemini' };
    }

    return { success: true, text };
  }

  static async rewriteWithGroq(prompt: string): Promise<ScribeRewriteResult> {
    if (!GROQ_API_KEY) {
      return { success: false, error: 'Groq API key not configured' };
    }

    const response = await fetch('https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + GROQ_API_KEY,
      },
      body: JSON.stringify({
        model: 'llama3-8b-8192',
        messages: [{ role: 'user', content: prompt }],
        temperature: 0.3,
        max_tokens: 1024,
      }),
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      return { success: false, error: error?.error?.message || 'Groq API error' };
    }

    const data = await response.json();
    const text = data?.choices?.[0]?.message?.content?.trim();

    if (!text) {
      return { success: false, error: 'No text returned from Groq' };
    }

    return { success: true, text };
  }

  static isConfigured(model: 'gemini' | 'groq'): boolean {
    if (model === 'gemini') return !!GEMINI_API_KEY;
    return !!GROQ_API_KEY;
  }
}
