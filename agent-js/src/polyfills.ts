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
