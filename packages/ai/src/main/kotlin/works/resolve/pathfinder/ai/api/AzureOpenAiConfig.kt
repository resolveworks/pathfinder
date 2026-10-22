package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.utils.trimJsWhitespace

internal const val DEFAULT_AZURE_API_VERSION = "v1"

/** Azure models ship without a baseUrl: one resource per user, resolved per request. */
interface AzureEndpointOptions {
    val env: Map<String, String>
    val azureApiVersion: String? get() = null
    val azureResourceName: String? get() = null
    val azureBaseUrl: String? get() = null
    val azureDeploymentName: String? get() = null
}

/** Parses `modelId=deployment,modelId2=deployment2` entries. */
internal fun parseDeploymentNameMap(value: String?): Map<String, String> {
    if (value.isNullOrBlank()) return emptyMap()
    val map = mutableMapOf<String, String>()
    for (entry in value.split(",")) {
        val trimmed = trimJsWhitespace(entry)
        if (trimmed.isEmpty()) continue
        val modelId = trimJsWhitespace(trimmed.substringBefore("=", ""))
        val deploymentName = trimJsWhitespace(trimmed.substringAfter("=", ""))
        if (modelId.isEmpty() || deploymentName.isEmpty()) continue
        map[modelId] = deploymentName
    }
    return map
}

internal fun resolveDeploymentName(model: Model, options: AzureEndpointOptions?): String {
    options?.azureDeploymentName?.let { return it }
    val mapped = parseDeploymentNameMap(
        options?.env?.get("AZURE_OPENAI_DEPLOYMENT_NAME_MAP")
    )[model.id]
    return mapped ?: model.id
}

/** Query and fragment are preserved verbatim; only the Azure-host path
 * rewrite clears the query (pi clears url.search inside that branch only). */
internal fun normalizeAzureBaseUrl(raw: String): String {
    val trimmed = trimJsWhitespace(raw).trimEnd('/')
    val url = try {
        java.net.URI(trimmed)
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid Azure OpenAI base URL: $raw")
    }
    val host = url.host ?: throw IllegalArgumentException("Invalid Azure OpenAI base URL: $raw")
    val isAzureHost = host.endsWith(".openai.azure.com") ||
        host.endsWith(".cognitiveservices.azure.com") ||
        host.endsWith(".ai.azure.com")
    var effectivePath = (url.path ?: "").trimEnd('/')
    var stripQuery = false
    if (isAzureHost &&
        (
            effectivePath.isEmpty() || effectivePath == "/openai" ||
                effectivePath == "/openai/v1/responses"
            )
    ) {
        effectivePath = "/openai/v1"
        stripQuery = true
    }
    val port = if (url.port != -1) ":${url.port}" else ""
    val userInfo = url.userInfo?.takeIf { it.isNotEmpty() }?.let { "$it@" } ?: ""
    val query = if (stripQuery) {
        ""
    } else {
        url.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" }
            ?: ""
    }
    val fragment = url.rawFragment?.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: ""
    return "${url.scheme}://$userInfo$host$port$effectivePath$query$fragment"
}

private fun buildDefaultBaseUrl(resourceName: String): String =
    "https://$resourceName.openai.azure.com/openai/v1"

internal fun resolveAzureBaseUrl(model: Model, options: AzureEndpointOptions?): String {
    val envBaseUrl = options?.env?.get("AZURE_OPENAI_BASE_URL")?.let(::trimJsWhitespace)
    // pi's `azureBaseUrl?.trim() || env?.trim() || undefined`: an empty
    // (post-trim) override falls through to the env value.
    val baseUrl = options?.azureBaseUrl?.let(::trimJsWhitespace)
        ?.takeIf { it.isNotEmpty() }
        ?: envBaseUrl?.takeIf { it.isNotEmpty() }
    val resourceName = options?.azureResourceName ?: options?.env?.get("AZURE_OPENAI_RESOURCE_NAME")

    var resolvedBaseUrl = baseUrl
    if (resolvedBaseUrl == null && resourceName != null) {
        resolvedBaseUrl = buildDefaultBaseUrl(resourceName)
    }
    if (resolvedBaseUrl == null && model.baseUrl.isNotEmpty()) {
        resolvedBaseUrl = model.baseUrl
    }
    if (resolvedBaseUrl == null) {
        throw IllegalStateException(
            "Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or " +
                "AZURE_OPENAI_RESOURCE_NAME, or pass azureBaseUrl, azureResourceName, or model.baseUrl."
        )
    }
    return normalizeAzureBaseUrl(resolvedBaseUrl)
}

internal data class AzureConfig(val baseUrl: String, val apiVersion: String)

internal fun resolveAzureConfig(model: Model, options: AzureEndpointOptions?): AzureConfig =
    AzureConfig(
        baseUrl = resolveAzureBaseUrl(model, options),
        apiVersion = options?.azureApiVersion
            ?: options?.env?.get("AZURE_OPENAI_API_VERSION")
            ?: DEFAULT_AZURE_API_VERSION
    )
