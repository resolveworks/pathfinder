import { Agent } from "@earendil-works/pi-agent-core";
import { createModels } from "@earendil-works/pi-ai";
import { fauxAssistantMessage, fauxProvider } from "@earendil-works/pi-ai/providers/faux";
import { emit, errorFields, log, type LogFields } from "./protocol";

/** Owns pi state while keeping the global QuickJS bridge deliberately small. */
export class AletheiaAgentRuntime {
  private agent: Agent | undefined;
  private preparePrompt: (() => void) | undefined;

  initialize(providerId: string, modelId: string): void {
    if (this.agent) throw new Error("Runtime is already initialized");
    log("info", "initialize_started", { providerId, modelId });

    // Faux is deliberately the first vertical slice: it exercises the real pi Agent and
    // event stream in QuickJS without hiding networking behind a second implementation.
    if (providerId !== "faux") {
      throw new Error(`Provider '${providerId}' is not bundled yet`);
    }

    const resolvedModelId = modelId || "faux-1";
    const models = createModels();
    const faux = fauxProvider({ provider: "faux", models: [{ id: resolvedModelId }] });
    const queueResponse = (): void => {
      if (faux.getPendingResponseCount() === 0) {
        faux.appendResponses([fauxAssistantMessage("QuickJS is running pi-agent-core.")]);
      }
      log("debug", "faux_response_ready", {
        pendingResponses: faux.getPendingResponseCount(),
      });
    };
    queueResponse();
    this.preparePrompt = queueResponse;
    models.setProvider(faux.provider);
    log("debug", "faux_provider_ready", { modelId: resolvedModelId });

    const model = models.getModel("faux", resolvedModelId);
    if (!model) throw new Error(`Unknown model '${resolvedModelId}'`);

    this.agent = new Agent({
      initialState: { systemPrompt: "You are a helpful assistant.", model },
      streamFn: models.streamSimple.bind(models),
    });
    this.agent.subscribe((event) => {
      const fields: LogFields = { eventType: event.type };
      if (event.type === "message_update") {
        fields.updateType = event.assistantMessageEvent.type;
      } else if (event.type === "message_end") {
        fields.role = event.message.role;
        if (event.message.role === "assistant") {
          fields.stopReason = event.message.stopReason;
          fields.hasError = Boolean(event.message.errorMessage);
        }
      } else if (event.type === "agent_end") {
        fields.messageCount = event.messages.length;
      }
      log("debug", "agent_event", fields);

      if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
        emit({ type: "text_delta", delta: event.assistantMessageEvent.delta });
      } else if (event.type === "message_end" && event.message.role === "assistant") {
        if (event.message.stopReason === "aborted") {
          log("info", "assistant_message_aborted");
          emit({ type: "message_end" });
        } else if (event.message.errorMessage) {
          log("error", "assistant_message_failed", {
            stopReason: event.message.stopReason,
            errorMessage: event.message.errorMessage,
          });
          emit({ type: "error", message: event.message.errorMessage });
        } else {
          emit({ type: "message_end" });
        }
      } else if (event.type === "agent_end") {
        emit({ type: "agent_end" });
      }
    });

    log("info", "initialize_completed", { providerId: model.provider, modelId: model.id });
    emit({ type: "initialized", providerId: model.provider, modelId: model.id });
  }

  async prompt(text: string): Promise<void> {
    if (!this.agent) throw new Error("Runtime is not initialized");
    const startedAt = Date.now();
    log("info", "prompt_started", { textLength: text.length });
    try {
      this.preparePrompt?.();
      await this.agent.prompt(text);
      log("info", "prompt_completed", { durationMs: Date.now() - startedAt });
    } catch (error) {
      log("error", "prompt_failed", {
        durationMs: Date.now() - startedAt,
        ...errorFields(error),
      });
      throw error;
    }
  }

  abort(): void {
    log("info", "abort_requested", { hasActiveRun: Boolean(this.agent?.signal) });
    this.agent?.abort();
  }
}
