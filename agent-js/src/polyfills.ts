import { log, type LogLevel } from "./protocol";

// QuickJS does not provide console. Forward metadata only: dependency messages may contain
// prompts or credentials, so their values must not cross the logging boundary.
if (typeof globalThis.console !== "object") {
  const write = (level: LogLevel, method: string, args: unknown[]): void => {
    log(level, "console_call", {
      method,
      argumentCount: args.length,
      argumentTypes: args.map((value) => typeof value).join(","),
    });
  };
  globalThis.console = {
    debug: (...args: unknown[]) => write("debug", "debug", args),
    log: (...args: unknown[]) => write("debug", "log", args),
    info: (...args: unknown[]) => write("info", "info", args),
    warn: (...args: unknown[]) => write("warn", "warn", args),
    error: (...args: unknown[]) => write("error", "error", args),
  } as unknown as Console;
}

// QuickJS provides ECMAScript but not the browser text encoding API used by pi-ai.
if (typeof globalThis.TextEncoder !== "function") {
  globalThis.TextEncoder = class TextEncoder {
    readonly encoding = "utf-8";

    encode(input = ""): Uint8Array {
      const bytes: number[] = [];
      for (const character of input) {
        let codePoint = character.codePointAt(0)!;
        if (codePoint <= 0x7f) {
          bytes.push(codePoint);
        } else if (codePoint <= 0x7ff) {
          bytes.push(0xc0 | (codePoint >> 6), 0x80 | (codePoint & 0x3f));
        } else if (codePoint <= 0xffff) {
          bytes.push(
            0xe0 | (codePoint >> 12),
            0x80 | ((codePoint >> 6) & 0x3f),
            0x80 | (codePoint & 0x3f),
          );
        } else {
          bytes.push(
            0xf0 | (codePoint >> 18),
            0x80 | ((codePoint >> 12) & 0x3f),
            0x80 | ((codePoint >> 6) & 0x3f),
            0x80 | (codePoint & 0x3f),
          );
        }
      }
      return Uint8Array.from(bytes);
    }
  } as typeof TextEncoder;
}

if (typeof globalThis.AbortController !== "function") {
  class QuickJsAbortSignal {
    aborted = false;
    reason: unknown;
    onabort: ((event: Event) => unknown) | null = null;
    private readonly listeners = new Set<(event: Event) => unknown>();

    addEventListener(type: string, listener: EventListenerOrEventListenerObject | null): void {
      if (type !== "abort" || listener === null) return;
      const callback = typeof listener === "function" ? listener : listener.handleEvent.bind(listener);
      this.listeners.add(callback);
    }

    removeEventListener(type: string, listener: EventListenerOrEventListenerObject | null): void {
      if (type === "abort" && typeof listener === "function") this.listeners.delete(listener);
    }

    throwIfAborted(): void {
      if (this.aborted) throw this.reason;
    }

    dispatchAbort(reason?: unknown): void {
      if (this.aborted) return;
      this.aborted = true;
      this.reason = reason ?? Object.assign(new Error("The operation was aborted"), { name: "AbortError" });
      const event = { type: "abort", target: this } as unknown as Event;
      this.onabort?.(event);
      for (const listener of this.listeners) listener(event);
      this.listeners.clear();
    }
  }

  globalThis.AbortController = class AbortController {
    readonly signal = new QuickJsAbortSignal() as unknown as AbortSignal;

    abort(reason?: unknown): void {
      (this.signal as unknown as QuickJsAbortSignal).dispatchAbort(reason);
    }
  } as typeof AbortController;
}

if (typeof globalThis.queueMicrotask !== "function") {
  globalThis.queueMicrotask = (callback: VoidFunction): void => {
    void Promise.resolve().then(callback);
  };
}

// The faux provider clones scripted responses when materializing them.
if (typeof globalThis.structuredClone !== "function") {
  globalThis.structuredClone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
}
