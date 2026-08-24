package works.resolve.aletheia.ai.testing

import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.providers.CatalogProvider
import works.resolve.aletheia.ai.providers.ProviderCatalog

/**
 * Shared inline catalog fixture: the Z.AI provider reproducing the values of
 * the retired hand-written ZaiModels port (so payload/stream/VM tests keep
 * identical model metadata), plus a Cloudflare-style gateway provider used to
 * exercise placeholder substitution and the bearer-header override.
 */
object TestCatalogs {

    private const val ZAI_BASE_URL = "https://api.z.ai/api/coding/paas/v4"

    val CATALOG: ProviderCatalog = ProviderCatalog.parse(
        """
        {
          "generatedAt": "test",
          "providers": [
            {
              "id": "zai",
              "name": "Z.AI",
              "baseUrl": "$ZAI_BASE_URL",
              "auth": {
                "label": "Z.AI API key",
                "prompts": [
                  {"envKey": "ZAI_API_KEY", "message": "Enter Z.AI API key", "secret": true}
                ]
              },
              "models": [
                {
                  "id": "glm-4.7", "name": "GLM-4.7",
                  "api": "openai-completions", "provider": "zai", "baseUrl": "$ZAI_BASE_URL",
                  "reasoning": true, "input": ["text"],
                  "cost": {"input": 0.6, "output": 2.2, "cacheRead": 0.11, "cacheWrite": 0},
                  "compat": {
                    "supportsStore": false, "supportsDeveloperRole": false,
                    "supportsReasoningEffort": false, "supportsUsageInStreaming": true,
                    "supportsFinishReason": true, "maxTokensField": "max_tokens",
                    "thinkingFormat": "zai", "zaiToolStream": true
                  },
                  "contextWindow": 204800, "maxTokens": 131072
                },
                {
                  "id": "glm-5-turbo", "name": "GLM-5-Turbo",
                  "api": "openai-completions", "provider": "zai", "baseUrl": "$ZAI_BASE_URL",
                  "reasoning": true, "input": ["text"],
                  "cost": {"input": 1.2, "output": 4.0, "cacheRead": 0.24, "cacheWrite": 0},
                  "compat": {
                    "supportsStore": false, "supportsDeveloperRole": false,
                    "supportsReasoningEffort": false, "supportsUsageInStreaming": true,
                    "supportsFinishReason": true, "maxTokensField": "max_tokens",
                    "thinkingFormat": "zai", "zaiToolStream": true
                  },
                  "contextWindow": 200000, "maxTokens": 131072
                },
                {
                  "id": "glm-5.3", "name": "GLM-5.3",
                  "api": "openai-completions", "provider": "zai", "baseUrl": "$ZAI_BASE_URL",
                  "reasoning": true,
                  "thinkingLevelMap": {"off": null, "minimal": null, "low": "low", "medium": null, "high": "high", "xhigh": null, "max": "max"},
                  "input": ["text"],
                  "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0},
                  "compat": {
                    "supportsStore": false, "supportsDeveloperRole": false,
                    "supportsReasoningEffort": true, "supportsUsageInStreaming": true,
                    "supportsFinishReason": true, "maxTokensField": "max_tokens",
                    "thinkingFormat": "zai", "zaiToolStream": true
                  },
                  "contextWindow": 1000000, "maxTokens": 131072
                },
                {
                  "id": "glm-5.2", "name": "GLM-5.2",
                  "api": "openai-completions", "provider": "zai", "baseUrl": "$ZAI_BASE_URL",
                  "reasoning": true,
                  "thinkingLevelMap": {"off": "none", "minimal": null, "low": null, "medium": null, "high": "high", "xhigh": null, "max": "max"},
                  "input": ["text"],
                  "cost": {"input": 1.4, "output": 4.4, "cacheRead": 0.26, "cacheWrite": 0},
                  "compat": {
                    "supportsStore": false, "supportsDeveloperRole": false,
                    "supportsReasoningEffort": true, "supportsUsageInStreaming": true,
                    "supportsFinishReason": true, "maxTokensField": "max_tokens",
                    "thinkingFormat": "zai", "zaiToolStream": true
                  },
                  "contextWindow": 1000000, "maxTokens": 131072
                },
                {
                  "id": "glm-5.2-highspeed", "name": "GLM-5.2 Highspeed",
                  "api": "openai-completions", "provider": "zai", "baseUrl": "$ZAI_BASE_URL",
                  "reasoning": true,
                  "thinkingLevelMap": {"off": "none", "minimal": null, "low": null, "medium": null, "high": "high", "xhigh": null, "max": "max"},
                  "input": ["text"],
                  "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0},
                  "compat": {
                    "supportsStore": false, "supportsDeveloperRole": false,
                    "supportsReasoningEffort": true, "supportsUsageInStreaming": true,
                    "supportsFinishReason": true, "maxTokensField": "max_tokens",
                    "thinkingFormat": "zai", "zaiToolStream": true
                  },
                  "contextWindow": 1000000, "maxTokens": 131072
                }
              ]
            },
            {
              "id": "cloudflare-ai-gateway",
              "name": "Cloudflare AI Gateway",
              "baseUrl": "https://gateway.test/v1/{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/compat",
              "bearerHeaderName": "cf-aig-authorization",
              "auth": {
                "label": "Cloudflare API key",
                "prompts": [
                  {"envKey": "CLOUDFLARE_API_KEY", "message": "Enter Cloudflare API key", "secret": true},
                  {"envKey": "CLOUDFLARE_ACCOUNT_ID", "message": "Enter Cloudflare account ID", "secret": false},
                  {"envKey": "CLOUDFLARE_GATEWAY_ID", "message": "Enter gateway ID", "secret": false}
                ]
              },
              "models": [
                {
                  "id": "workers-ai/test-model", "name": "Workers AI Test Model",
                  "api": "openai-completions", "provider": "cloudflare-ai-gateway",
                  "input": ["text"],
                  "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0},
                  "compat": {"supportsStore": false, "supportsDeveloperRole": false},
                  "contextWindow": 128000, "maxTokens": 8192
                }
              ]
            },
            {
              "id": "github-copilot",
              "name": "GitHub Copilot",
              "baseUrl": "https://api.individual.githubcopilot.com",
              "auth": {
                "label": "GitHub Copilot token",
                "oauth": {"name": "GitHub Copilot", "isSubscription": true},
                "prompts": [
                  {"envKey": "COPILOT_GITHUB_TOKEN", "message": "Enter GitHub Copilot token", "secret": true}
                ]
              },
              "models": [
                {
                  "id": "claude-haiku-4.5", "name": "Claude Haiku 4.5",
                  "api": "openai-completions", "provider": "github-copilot",
                  "baseUrl": "https://api.individual.githubcopilot.com",
                  "input": ["text"],
                  "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0},
                  "compat": {"supportsStore": false, "supportsDeveloperRole": false},
                  "contextWindow": 200000, "maxTokens": 8192
                },
                {
                  "id": "gpt-4.1", "name": "GPT-4.1",
                  "api": "openai-completions", "provider": "github-copilot",
                  "baseUrl": "https://api.individual.githubcopilot.com",
                  "input": ["text"],
                  "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0},
                  "compat": {"supportsStore": false, "supportsDeveloperRole": false},
                  "contextWindow": 1000000, "maxTokens": 32768
                },
                {
                  "id": "gpt-4.5", "name": "GPT-4.5",
                  "api": "openai-completions", "provider": "github-copilot",
                  "baseUrl": "https://api.individual.githubcopilot.com",
                  "input": ["text"],
                  "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0},
                  "compat": {"supportsStore": false, "supportsDeveloperRole": false},
                  "contextWindow": 400000, "maxTokens": 32768
                }
              ]
            },
            {
              "id": "oauth-only",
              "name": "OAuth Only",
              "baseUrl": "https://oauth.test/v1",
              "auth": {},
              "models": [
                {
                  "id": "account-model", "name": "Account Model",
                  "api": "openai-completions", "provider": "oauth-only",
                  "input": ["text"],
                  "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0},
                  "compat": {"supportsStore": false, "supportsDeveloperRole": false},
                  "contextWindow": 128000, "maxTokens": 8192
                }
              ]
            }
          ]
        }
        """,
    )

    val ZAI: CatalogProvider = CATALOG.getProvider("zai")!!

    val CLOUDFLARE: CatalogProvider = CATALOG.getProvider("cloudflare-ai-gateway")!!

    /** Promptless provider: OAuth-only (pi's openai-codex shape). */
    val OAUTH_ONLY: CatalogProvider = CATALOG.getProvider("oauth-only")!!

    /** GitHub Copilot fixture mirroring the generated catalog entry's shape
     * (API-key prompt + subscription OAuth, three static models). */
    val GITHUB_COPILOT: CatalogProvider = CATALOG.getProvider("github-copilot")!!

    val MODELS: List<Model> = ZAI.models

    val GLM_4_7: Model = ZAI.model("glm-4.7")!!

    val GLM_5_TURBO: Model = ZAI.model("glm-5-turbo")!!

    val GLM_5_3: Model = ZAI.model("glm-5.3")!!

    val GLM_5_2: Model = ZAI.model("glm-5.2")!!

    val GLM_5_2_HIGHSPEED: Model = ZAI.model("glm-5.2-highspeed")!!
}
