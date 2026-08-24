#!/usr/bin/env node
// Generates app/src/main/assets/models-catalog.json for aletheia.
//
// Runs pi's model-catalog generator (models.dev et al.) from a local pi
// checkout, keeps only the providers aletheia supports (OpenAI Chat
// Completions API), and merges each provider's hand-curated identity —
// display name and API-key auth metadata — mirroring how pi splits generated
// model data (src/providers/data/*.json) from hand-written provider files
// (src/providers/*.ts).
//
// The generator also guards against silent drift in the upstream pi
// checkout, since the asset ships inside the APK and any mismatch with the
// Kotlin runtime (ProviderCatalog.kt) is a build bug, not a runtime
// condition. It fails when:
//   - pi learns a new (or drops a known) openai-completions provider that is
//     not reflected in PROVIDER_IDENTITY (or EXCLUDED_PROVIDERS) below;
//   - a model uses a compat field that the Kotlin runtime does not model and
//     that is not in UNSUPPORTED_COMPAT_FIELDS (fields intentionally
//     irrelevant to this MVP);
//   - a model uses a thinkingFormat / maxTokensField / input modality value
//     the Kotlin enums do not know.
//
// Usage:
//   node tools/generate-model-catalog.mjs          # PI_REPO_DIR or ~/Projects/pi
//   node tools/generate-model-catalog.mjs --test   # self-test validation helpers
//   PI_REPO_DIR=/path/to/pi node tools/generate-model-catalog.mjs
//
// pi's env-var auth model maps onto Android GUI inputs: each auth prompt
// (env key, message, secret/plain) becomes one input field, and the stored
// values substitute {ENV_KEY} placeholders in model base URLs (Cloudflare
// account/gateway ids). bearerHeaderName mirrors pi's auth-header overrides.

import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, "..");
const piRepo = process.env.PI_REPO_DIR ?? join(process.env.HOME ?? "", "Projects", "pi");
const output = join(repoRoot, "app/src/main/assets/models-catalog.json");

const API = "openai-completions";

/**
 * Values the Kotlin runtime (ProviderCatalog.kt / Model.kt) can decode.
 * These mirror the Kotlin enums exactly; a new upstream value fails
 * generation so it must be ported before the asset can change.
 */
export const SUPPORTED_THINKING_FORMATS = /** @type {const} */ ([
	"openai",
	"zai",
	"qwen",
	"deepseek",
	"baseten",
	"openrouter",
	"ant-ling",
	"together",
]);
export const SUPPORTED_MAX_TOKENS_FIELDS = /** @type {const} */ ([
	"max_tokens",
	"max_completion_tokens",
]);
export const SUPPORTED_INPUT_MODALITIES = /** @type {const} */ (["text", "image"]);

/**
 * compat fields consumed by the Kotlin OpenAiCompletionsCompat type.
 */
export const SUPPORTED_COMPAT_FIELDS = /** @type {const} */ ([
	"supportsStore",
	"supportsDeveloperRole",
	"supportsReasoningEffort",
	"supportsUsageInStreaming",
	"supportsFinishReason",
	"maxTokensField",
	"requiresToolResultName",
	"requiresThinkingAsText",
	"thinkingFormat",
	"zaiToolStream",
	"chatTemplateArgs",
]);

/**
 * compat fields pi emits that aletheia deliberately does not model yet.
 * These are an explicit, reviewed decision about current scope — not a claim
 * of irrelevance. Grouped by why they are unmodeled:
 *   - Protocol features this MVP never sends (strict tool schemas,
 *     prompt-cache retention, deferred tool scheduling, Anthropic-style
 *     cache control): supportsStrictMode, supportsLongCacheRetention,
 *     deferredToolsMode, cacheControlFormat.
 *   - Runtime request/response behaviors that may become relevant for plain
 *     multi-turn or session-affinity support later, currently accepted as
 *     unmodeled: sendSessionAffinityHeaders,
 *     requiresReasoningContentOnAssistantMessages.
 * An unknown field not listed here fails generation; the Kotlin parser also
 * ignores listed fields via ignoreUnknownKeys as defense in depth.
 */
export const UNSUPPORTED_COMPAT_FIELDS = /** @type {const} */ ([
	// Not applicable to this MVP's request shapes.
	"supportsStrictMode",
	"supportsLongCacheRetention",
	"deferredToolsMode",
	"cacheControlFormat",
	// Accepted as unmodeled for current scope; revisit for multi-turn and
	// session-affinity work.
	"sendSessionAffinityHeaders",
	"requiresReasoningContentOnAssistantMessages",
]);

/**
 * openai-completions providers that pi generates but aletheia deliberately
 * does not ship. Adding a provider id here requires a comment explaining why.
 */
export const EXCLUDED_PROVIDERS = /** @type {const} */ ([]);

/** Cloudflare login prompts (pi: providers/cloudflare-auth.ts). */
const CLOUDFLARE_ACCOUNT = {
	envKey: "CLOUDFLARE_ACCOUNT_ID",
	message: "Enter Cloudflare account ID",
	secret: false,
};
const CLOUDFLARE_GATEWAY = {
	envKey: "CLOUDFLARE_GATEWAY_ID",
	message: "Enter Cloudflare AI Gateway ID",
	secret: false,
};

/**
 * Provider identity, mirroring pi's hand-written providers/*.ts entries that
 * use the openai-completions API. Keyed by provider id; `label` is the
 * envApiKeyAuth display name, `envKey` its environment variable.
 */
export const PROVIDER_IDENTITY = {
	"ant-ling": { name: "Ant Ling", label: "Ant Ling API key", envKey: "ANT_LING_API_KEY" },
	baseten: { name: "Baseten", label: "Baseten API key", envKey: "BASETEN_API_KEY" },
	cerebras: { name: "Cerebras", label: "Cerebras API key", envKey: "CEREBRAS_API_KEY" },
	"cloudflare-ai-gateway": {
		name: "Cloudflare AI Gateway",
		label: "Cloudflare API key",
		envKey: "CLOUDFLARE_API_KEY",
		extraPrompts: [CLOUDFLARE_ACCOUNT, CLOUDFLARE_GATEWAY],
		bearerHeaderName: "cf-aig-authorization",
	},
	"cloudflare-workers-ai": {
		name: "Cloudflare Workers AI",
		label: "Cloudflare API key",
		envKey: "CLOUDFLARE_API_KEY",
		extraPrompts: [CLOUDFLARE_ACCOUNT],
	},
	deepseek: { name: "DeepSeek", label: "DeepSeek API key", envKey: "DEEPSEEK_API_KEY" },
	fireworks: { name: "Fireworks", label: "Fireworks API key", envKey: "FIREWORKS_API_KEY" },
	"github-copilot": { name: "GitHub Copilot", label: "GitHub Copilot token", envKey: "COPILOT_GITHUB_TOKEN" },
	groq: { name: "Groq", label: "Groq API key", envKey: "GROQ_API_KEY" },
	huggingface: { name: "Hugging Face", label: "Hugging Face token", envKey: "HF_TOKEN" },
	moonshotai: { name: "Moonshot AI", label: "Moonshot AI API key", envKey: "MOONSHOT_API_KEY" },
	"moonshotai-cn": { name: "Moonshot AI CN", label: "Moonshot AI API key", envKey: "MOONSHOT_API_KEY" },
	nvidia: { name: "NVIDIA", label: "NVIDIA API key", envKey: "NVIDIA_API_KEY" },
	opencode: { name: "OpenCode Zen", label: "OpenCode API key", envKey: "OPENCODE_API_KEY" },
	"opencode-go": { name: "OpenCode Go", label: "OpenCode API key", envKey: "OPENCODE_API_KEY" },
	openrouter: { name: "OpenRouter", label: "OpenRouter API key", envKey: "OPENROUTER_API_KEY" },
	"qwen-token-plan": { name: "Qwen Token Plan", label: "Qwen Token Plan API key", envKey: "QWEN_TOKEN_PLAN_API_KEY" },
	"qwen-token-plan-cn": { name: "Qwen Token Plan CN", label: "Qwen Token Plan CN API key", envKey: "QWEN_TOKEN_PLAN_CN_API_KEY" },
	"qwen-token-plan-individual": {
		name: "Qwen Token Plan Individual",
		label: "Qwen Token Plan Individual API key",
		envKey: "QWEN_TOKEN_PLAN_API_KEY",
	},
	together: { name: "Together", label: "Together API key", envKey: "TOGETHER_API_KEY" },
	xiaomi: { name: "Xiaomi", label: "Xiaomi API key", envKey: "XIAOMI_API_KEY" },
	"xiaomi-token-plan-ams": { name: "Xiaomi Token Plan AMS", label: "Xiaomi Token Plan AMS API key", envKey: "XIAOMI_TOKEN_PLAN_AMS_API_KEY" },
	"xiaomi-token-plan-cn": { name: "Xiaomi Token Plan CN", label: "Xiaomi Token Plan CN API key", envKey: "XIAOMI_TOKEN_PLAN_CN_API_KEY" },
	"xiaomi-token-plan-sgp": { name: "Xiaomi Token Plan SGP", label: "Xiaomi Token Plan SGP API key", envKey: "XIAOMI_TOKEN_PLAN_SGP_API_KEY" },
	zai: { name: "Z.AI", label: "Z.AI API key", envKey: "ZAI_API_KEY" },
	"zai-coding-cn": { name: "Z.AI Coding CN", label: "Z.AI Coding CN API key", envKey: "ZAI_CODING_CN_API_KEY" },
};

/**
 * Derives every pi provider id that has at least one model using the target
 * API, from pi's generated models.json (shape: { [providerId]: { [modelId]:
 * model } }).
 */
export function openaiCompletionsProviderIds(piModels, api = API) {
	const ids = new Set();
	for (const [providerId, models] of Object.entries(piModels ?? {})) {
		if (Object.values(models ?? {}).some((model) => model?.api === api)) ids.add(providerId);
	}
	return ids;
}

/**
 * Validates that the curated PROVIDER_IDENTITY set exactly matches the
 * providers pi currently generates for the target API, modulo the explicit
 * EXCLUDED_PROVIDERS allowlist. Throws with an actionable message on drift.
 */
export function validateProviderSet(piModels) {
	const generated = openaiCompletionsProviderIds(piModels);
	const curated = new Set(Object.keys(PROVIDER_IDENTITY));
	const excluded = new Set(EXCLUDED_PROVIDERS);
	for (const id of excluded) {
		if (curated.has(id)) {
			throw new Error(`EXCLUDED_PROVIDERS entry '${id}' is also in PROVIDER_IDENTITY`);
		}
	}
	const missing = [...generated].filter((id) => !curated.has(id) && !excluded.has(id)).sort();
	const stale = [...curated].filter((id) => !generated.has(id)).sort();
	if (missing.length > 0) {
		throw new Error(
			`pi now generates ${API} models for providers aletheia does not curate: ${missing.join(", ")}. ` +
				"Add identity entries (or EXCLUDED_PROVIDERS with a rationale) in tools/generate-model-catalog.mjs.",
		);
	}
	if (stale.length > 0) {
		throw new Error(
			`PROVIDER_IDENTITY contains providers pi no longer generates ${API} models for: ${stale.join(", ")}. ` +
				"Remove them in tools/generate-model-catalog.mjs.",
		);
	}
}

/**
 * Validates one generated model's compat block and input modalities against
 * the values the Kotlin runtime can decode. Throws on drift so generation
 * fails before the asset reaches the app.
 */
export function validateModelEnums(model, where = `${model?.provider ?? "?"}/${model?.id ?? "?"}`) {
	const compat = model?.compat ?? {};
	for (const field of Object.keys(compat)) {
		if (
			!SUPPORTED_COMPAT_FIELDS.includes(field) &&
			!UNSUPPORTED_COMPAT_FIELDS.includes(field)
		) {
			throw new Error(
				`Unknown compat field '${field}' on ${where}. Either model it in Kotlin ` +
					"(OpenAiCompletionsCompat) or add it to UNSUPPORTED_COMPAT_FIELDS with a rationale.",
			);
		}
	}
	if (compat.thinkingFormat != null && !SUPPORTED_THINKING_FORMATS.includes(compat.thinkingFormat)) {
		throw new Error(
			`Unsupported thinkingFormat '${compat.thinkingFormat}' on ${where} ` +
				`(known: ${SUPPORTED_THINKING_FORMATS.join(", ")})`,
		);
	}
	if (compat.maxTokensField != null && !SUPPORTED_MAX_TOKENS_FIELDS.includes(compat.maxTokensField)) {
		throw new Error(
			`Unsupported maxTokensField '${compat.maxTokensField}' on ${where} ` +
				`(known: ${SUPPORTED_MAX_TOKENS_FIELDS.join(", ")})`,
		);
	}
	for (const modality of model?.input ?? ["text"]) {
		if (!SUPPORTED_INPUT_MODALITIES.includes(modality)) {
			throw new Error(
				`Unsupported input modality '${modality}' on ${where} ` +
					`(known: ${SUPPORTED_INPUT_MODALITIES.join(", ")})`,
			);
		}
	}
}

/**
 * Builds the aletheia catalog from pi's generated models. Pure: takes pi's
 * models.json plus provenance and returns the catalog object. Throws on any
 * provider-set or enum drift (see validateProviderSet / validateModelEnums).
 */
export function buildCatalog(piModels, { piRevision = null } = {}) {
	validateProviderSet(piModels);
	const providers = [];

	for (const [providerId, identity] of Object.entries(PROVIDER_IDENTITY)) {
		const models = Object.values(piModels[providerId] ?? {}).filter((model) => model.api === API);
		if (models.length === 0) {
			throw new Error(`No ${API} models generated for known provider: ${providerId}`);
		}
		for (const model of models) validateModelEnums(model, `${providerId}/${model.id}`);
		const baseUrls = [...new Set(models.map((model) => model.baseUrl))];
		if (baseUrls.length !== 1) {
			throw new Error(`Provider ${providerId} has inconsistent base URLs: ${baseUrls.join(", ")}`);
		}
		const entry = {
			id: providerId,
			name: identity.name,
			baseUrl: baseUrls[0],
			auth: {
				label: identity.label,
				prompts: [
					{ envKey: identity.envKey, message: `Enter ${identity.label}`, secret: true },
					...(identity.extraPrompts ?? []),
				],
			},
			models,
		};
		if (identity.bearerHeaderName) entry.bearerHeaderName = identity.bearerHeaderName;
		providers.push(entry);
	}

	providers.sort((a, b) => a.id.localeCompare(b.id));
	return {
		// Provenance: the pi git revision the models were generated from, so
		// the asset is byte-stable across regenerations from one checkout.
		piRevision,
		providers,
	};
}

function generatePiCatalog() {
	const packageDir = join(piRepo, "packages", "ai");
	const tmp = mkdtempSync(join(tmpdir(), "aletheia-model-catalog-"));
	try {
		execFileSync(
			process.execPath,
			["scripts/generate-models.ts", "--strict", "--json-only", "--json-output", tmp],
			{ cwd: packageDir, stdio: "inherit" },
		);
		return JSON.parse(readFileSync(join(tmp, "models.json"), "utf8"));
	} finally {
		rmSync(tmp, { recursive: true, force: true });
	}
}

/** Short git revision of the pi checkout, or null if it is not a git repo. */
function piGitRevision() {
	try {
		return execFileSync("git", ["-C", piRepo, "rev-parse", "--short", "HEAD"], {
			encoding: "utf8",
		}).trim();
	} catch {
		return null;
	}
}

/** Minimal inline fixtures asserting the drift guards behave as documented. */
function selfTest() {
	const assert = (condition, message) => {
		if (!condition) throw new Error(`self-test failed: ${message}`);
	};

	const baseModel = {
		id: "m",
		api: API,
		baseUrl: "https://example.test/v1",
		compat: { thinkingFormat: "openai", maxTokensField: "max_tokens" },
		input: ["text"],
	};
	const expectsError = (fn, needle) => {
		try {
			fn();
		} catch (error) {
			assert(String(error.message).includes(needle), `expected '${needle}' in: ${error.message}`);
			return;
		}
		throw new Error(`expected error containing '${needle}'`);
	};

	validateModelEnums(baseModel);
	assert(baseModel.compat.thinkingFormat === "openai");

	expectsError(
		() => validateModelEnums({ ...baseModel, compat: { thinkingFormat: "acme" } }),
		"Unsupported thinkingFormat 'acme'",
	);
	expectsError(
		() => validateModelEnums({ ...baseModel, compat: { maxTokensField: "tokens_max" } }),
		"Unsupported maxTokensField 'tokens_max'",
	);
	expectsError(
		() => validateModelEnums({ ...baseModel, input: ["video"] }),
		"Unsupported input modality 'video'",
	);
	expectsError(
		() => validateModelEnums({ ...baseModel, compat: { quantumEntangle: true } }),
		"Unknown compat field 'quantumEntangle'",
	);
	// Allowlisted unsupported fields pass through.
	validateModelEnums({ ...baseModel, compat: { supportsStrictMode: true } });

	const piModels = {};
	for (const id of Object.keys(PROVIDER_IDENTITY)) {
		piModels[id] = { m: { ...baseModel, id, provider: id } };
	}
	const catalog = buildCatalog(piModels, { piRevision: "deadbee" });
	assert(catalog.piRevision === "deadbee", "provenance recorded");
	assert(catalog.providers.length === Object.keys(PROVIDER_IDENTITY).length, "all providers kept");
	assert(catalog.providers[0].id < catalog.providers.at(-1).id, "providers sorted");
	assert(catalog.providers[0].auth.prompts[0].secret === true, "api-key prompt is secret");

	expectsError(() => validateProviderSet({ ...piModels, acme: { m: { ...baseModel } } }), "acme");
	expectsError(() => validateProviderSet({}), "no longer generates");

	console.log("self-test passed");
}

const isCli = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isCli) {
	if (process.argv.includes("--test")) {
		selfTest();
	} else {
		const catalog = buildCatalog(generatePiCatalog(), { piRevision: piGitRevision() });
		const totalModels = catalog.providers.reduce((sum, p) => sum + p.models.length, 0);
		writeFileSync(output, JSON.stringify(catalog) + "\n");
		console.log(`Wrote ${output}: ${catalog.providers.length} providers, ${totalModels} ${API} models`);
	}
}
