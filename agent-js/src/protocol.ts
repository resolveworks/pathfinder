export type LogLevel = "debug" | "info" | "warn" | "error";
export type LogFields = Record<string, string | number | boolean>;

export type AppEvent =
  | { type: "initialized"; providerId: string; modelId: string }
  | { type: "text_delta"; delta: string }
  | { type: "message_end" }
  | { type: "agent_end" }
  | { type: "error"; message: string };

declare global {
  var aletheiaEmit: (eventJson: string) => void;
  var aletheiaLog: (entryJson: string) => void;
}

export function emit(event: AppEvent): void {
  globalThis.aletheiaEmit(JSON.stringify(event));
}

/** Logging is best-effort and must never interrupt the agent. */
export function log(level: LogLevel, event: string, fields: LogFields = {}): void {
  try {
    globalThis.aletheiaLog(JSON.stringify({ level, event, fields }));
  } catch {
    // The Android host owns logging. There is no safe fallback inside QuickJS.
  }
}

export function errorFields(error: unknown): LogFields {
  if (error instanceof Error) {
    return { errorName: error.name, errorMessage: error.message };
  }
  return { errorName: typeof error };
}
