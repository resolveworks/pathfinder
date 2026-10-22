package works.resolve.pathfinder.ai.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.TranscriptContext

/**
 * Azure's Chat Completions dispatch: resolve the endpoint onto the model
 * before the shared adapter reads it, then send the deployment name as the
 * request's model while keeping the catalog id on the message.
 *
 * Resolution runs synchronously so the Models layer's lazyStream equivalent
 * turns an unconfigured endpoint into a terminal error event.
 */
internal class AzureOpenAiCompletionsChatApi(private val delegate: ChatApi) : ChatApi {
    private data class EndpointOptions(override val env: Map<String, String>) : AzureEndpointOptions

    override fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions
    ): Flow<AssistantMessageEvent> {
        val endpointOptions = EndpointOptions(options.env)
        val resolvedModel = model.copy(
            baseUrl = resolveAzureBaseUrl(model, endpointOptions)
        )
        val deploymentName = resolveDeploymentName(model, endpointOptions)
        val resolvedOptions = if (deploymentName == model.id) {
            options
        } else {
            options.copy(
                onPayload = { payload, payloadModel ->
                    val params = JsonObject(
                        payload + ("model" to JsonPrimitive(deploymentName))
                    )
                    options.onPayload?.invoke(params, payloadModel) ?: params
                }
            )
        }
        return delegate.streamSimple(resolvedModel, context, resolvedOptions)
    }
}
