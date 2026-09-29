// Unsent answers per question, kept in this browser only (KTD7).
const key = (questionId: string) => `ai-interview:draft:${questionId}`;

export function readDraft(questionId: string) {
  try {
    return window.localStorage.getItem(key(questionId)) ?? "";
  }
  catch {
    return "";
  }
}

export function writeDraft(questionId: string, text: string) {
  try {
    if (text) window.localStorage.setItem(key(questionId), text);
    else window.localStorage.removeItem(key(questionId));
  }
  catch {
    // Storage can be unavailable; drafts are a convenience.
  }
}
