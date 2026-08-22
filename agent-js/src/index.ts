import { Agent } from "@earendil-works/pi-agent-core";
import { createModels } from "@earendil-works/pi-ai";
import { fauxAssistantMessage, fauxProvider } from "@earendil-works/pi-ai/providers/faux";

declare global {
  var aletheiaEmit: (eventJson: string) => void;
  var aletheiaCommand: (commandJson: string) => Promise<string>;
}

type Command =
  | { type: "initialize"; providerId: string; modelId: string; apiKey?: string; baseUrl?: string }
  | { type: "prompt"; text: string }
  | { type: "abort" };

// QuickJS intentionally exposes only ECMAScript, not browser conveniences.
// pi's faux provider uses structuredClone when materializing scripted responses.
if (typeof globalThis.structuredClone !== "function") {
  globalThis.structuredClone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
}

let agent: Agent | undefined;

function emit(event: unknown): void {
  globalThis.aletheiaEmit(JSON.stringify(event));
}

function initialize(command: Extract<Command, { type: "initialize" }>): void {
  // Faux is deliberately the first vertical slice: it exercises the real pi Agent and
  // event stream in QuickJS without hiding networking behind a second implementation.
  if (command.providerId !== "faux") {
    throw new Error(`Provider '${command.providerId}' is not bundled yet`);
  }
  const models = createModels();
  const faux = fauxProvider({ provider: "faux", models: [{ id: command.modelId || "faux-1" }] });
  faux.setResponses([fauxAssistantMessage("QuickJS is running pi-agent-core.")]);
  models.setProvider(faux.provider);
  const model = models.getModel("faux", command.modelId || "faux-1");
  if (!model) throw new Error(`Unknown model '${command.modelId}'`);

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

async function handle(command: Command): Promise<void> {
  switch (command.type) {
    case "initialize": initialize(command); return;
    case "prompt":
      if (!agent) throw new Error("Runtime is not initialized");
      await agent.prompt(command.text);
      return;
    case "abort": agent?.abort(); return;
  }
}

globalThis.aletheiaCommand = async (commandJson: string): Promise<string> => {
  try {
    await handle(JSON.parse(commandJson) as Command);
    return JSON.stringify({ ok: true });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    emit({ type: "error", message });
    return JSON.stringify({ ok: false, error: message });
  }
};
