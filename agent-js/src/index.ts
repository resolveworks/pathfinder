import "./polyfills";
import { Agent } from "@earendil-works/pi-agent-core";
import { createModels } from "@earendil-works/pi-ai";
import { fauxAssistantMessage, fauxProvider } from "@earendil-works/pi-ai/providers/faux";

declare global {
  var aletheiaEmit: (eventJson: string) => void;
  var aletheia: {
    initialize(providerId: string, modelId: string): void;
    prompt(text: string): Promise<void>;
    abort(): void;
  };
}

let agent: Agent | undefined;

function emit(event: unknown): void {
  globalThis.aletheiaEmit(JSON.stringify(event));
}

function initialize(providerId: string, modelId: string): void {
  // Faux is deliberately the first vertical slice: it exercises the real pi Agent and
  // event stream in QuickJS without hiding networking behind a second implementation.
  if (providerId !== "faux") {
    throw new Error(`Provider '${providerId}' is not bundled yet`);
  }
  const resolvedModelId = modelId || "faux-1";
  const models = createModels();
  const faux = fauxProvider({ provider: "faux", models: [{ id: resolvedModelId }] });
  faux.setResponses([fauxAssistantMessage("QuickJS is running pi-agent-core.")]);
  models.setProvider(faux.provider);
  const model = models.getModel("faux", resolvedModelId);
  if (!model) throw new Error(`Unknown model '${resolvedModelId}'`);

  agent = new Agent({
    initialState: { systemPrompt: "You are a helpful assistant.", model },
    streamFn: models.streamSimple.bind(models),
  });
  agent.subscribe((event) => {
    if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
      emit({ type: "text_delta", delta: event.assistantMessageEvent.delta });
    } else if (event.type === "message_end") {
      emit({ type: "message_end", message: event.message });
    } else if (event.type === "agent_end") {
      emit({ type: "agent_end" });
    }
  });
  emit({ type: "initialized", providerId: model.provider, modelId: model.id });
}

globalThis.aletheia = {
  initialize,
  async prompt(text: string): Promise<void> {
    if (!agent) throw new Error("Runtime is not initialized");
    await agent.prompt(text);
  },
  abort(): void {
    agent?.abort();
  },
};
