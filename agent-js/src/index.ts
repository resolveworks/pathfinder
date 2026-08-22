import "./polyfills";
import { AletheiaAgentRuntime } from "./agent-runtime";
import { errorFields, log } from "./protocol";

declare global {
  var aletheia: {
    initialize(providerId: string, modelId: string): void;
    prompt(text: string): Promise<void>;
    abort(): void;
  };
}

const runtime = new AletheiaAgentRuntime();

globalThis.aletheia = {
  initialize(providerId: string, modelId: string): void {
    try {
      runtime.initialize(providerId, modelId);
    } catch (error) {
      log("error", "initialize_failed", errorFields(error));
      throw error;
    }
  },
  prompt(text: string): Promise<void> {
    return runtime.prompt(text);
  },
  abort(): void {
    runtime.abort();
  },
};

log("info", "bundle_loaded");
