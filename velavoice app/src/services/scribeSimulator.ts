// Pure draft-generation behind the Scribe simulator in App.tsx.
//
// Extracted verbatim from `runScribeRewrite` so the style/instruction branching
// is unit-testable without timers or component state. The caller still wraps
// this in a setTimeout to simulate mobile LLM inference latency.

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

export function buildScribeDrafts(
  baseText: string,
  style: string,
  instruction: string
): string[] {
  const userText = baseText.trim();

  // Dynamic draft generation based on text contents
  const cleanText = userText.replace(/^(So, |um, |like, |eh, |uh, |er, |hm, |oh, )+/gi, '').trim();

  let base = cleanText;
  if (instruction.trim()) {
    const inst = instruction.trim().toLowerCase();
    if (inst.includes('spanish') || inst.includes('espanol') || inst.includes('español')) {
      return [
        `[Español] Con respecto a: ${cleanText}`,
        `[Español - Casual] Oye, sobre esto: ${cleanText.toLowerCase()}`,
        `[Español - Profesional] Estimado equipo, adjunto el detalle: ${cleanText}`
      ];
    }
    if (inst.includes('german') || inst.includes('deutsch')) {
      return [
        `[Deutsch] Bezüglich: ${cleanText}`,
        `[Deutsch - Casual] Hallo, hier ist der Text: ${cleanText.toLowerCase()}`,
        `[Deutsch - Info] Betreffend: ${cleanText}`
      ];
    }
    if (inst.includes('short') || inst.includes('brief') || inst.includes('concise')) {
      base = cleanText.substring(0, Math.min(cleanText.length, 60)) + '...';
    } else {
      base = `${cleanText} (${instruction.trim()})`;
    }
  }

  switch (style) {
    case 'Professional':
      return [
        `Regarding the issue: ${cap(base)} I wanted to confirm this details.`,
        `Please be advised that: ${cap(base)} Let me know if you would like to proceed.`,
        `Concerning the details: ${cap(base)} I will keep you updated on progress.`
      ];
    case 'Casual':
      return [
        `Hey! So: ${cap(base)} Let's catch up later.`,
        `Yeah, basically: ${base.toLowerCase()}`,
        `Just wanted to let you know: ${base} Let me know what you think!`
      ];
    case 'Bullet Points': {
      const sentences = base.split(/[.!?]+/).map(s => s.trim()).filter(s => s.length > 0);
      return [
        sentences.map(s => `- ${cap(s)}`).join('\n'),
        `Key Points:\n` + sentences.map((s, idx) => `${idx + 1}. ${cap(s)}`).join('\n'),
        sentences.map(s => `• [Action Item] ${cap(s)}`).join('\n')
      ];
    }
    case 'Email Draft': {
      const subject = base.split(/[.!?]+/)[0] || 'Update';
      return [
        `Subject: Update on ${subject}\n\nHi Team,\n\nI hope you are doing well.\n\n${cap(base)}\n\nBest regards,\n[Name]`,
        `Subject: Notes: ${subject}\n\nHi everyone,\n\nHere is a quick recap:\n${cap(base)}\n\nThanks,\n[Name]`,
        `Subject: Quick question re: ${subject}\n\nHello,\n\n${cap(base)}\n\nLet me know your availability.\n\nThank you,\n[Name]`
      ];
    }
    case 'Proofread':
    default: {
      const corrected = base
        .replace(/\b(i)\b/g, 'I')
        .replace(/\b(im)\b/gi, "I'm")
        .replace(/\b(ive)\b/gi, "I've")
        .replace(/\b(id)\b/gi, "I'd")
        .replace(/\b(ill)\b/gi, "I'll");
      return [
        cap(corrected),
        `Cleaned transcription: ${cap(corrected)}`,
        `Standard corrected text: ${cap(corrected)}`
      ];
    }
  }
}
