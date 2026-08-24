#!/usr/bin/env node
// Generates app/src/main/assets/models-catalog.json for aletheia.
//
// Runs pi's model-catalog generator (models.dev et al.) from a local pi
// checkout, keeps every static pi-ai provider and ALL of each provider's
// model APIs (not just openai-completions), and merges each provider's
// hand-curated identity — display name and API-key auth metadata —
// mirroring how pi splits generated model data (src/providers/data/*.json)
// from hand-written provider files (src/providers/*.ts).
//
// Dynamic providers (radius), llama.cpp, Amazon Bedrock, and
// image-generation providers are deliberately excluded. OAuth flows are out
// of scope, so OAuth-only providers keep their model list but carry no
// API-key auth (auth: null).
//
// Usage:
//   node tools/generate-model-catalog.mjs          # PI_REPO_DIR or ~/Projects/pi
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

/** Google Vertex AI login prompts (pi: providers/google-vertex.ts). */
const VERTEX_PROJECT = {
	envKey: "GOOGLE_CLOUD_PROJECT",
	message: "Enter Google Cloud project ID",
	secret: false,
};
const VERTEX_LOCATION = {
	envKey: "GOOGLE_CLOUD_LOCATION",
	message: "Enter Google Cloud location",
	secret: false,
};

/**
 * Provider identity, mirroring pi's hand-written providers/*.ts entries.
 * Must cover every static provider in pi's generated models.json EXCEPT
 * the deliberately excluded ones in EXCLUDED_PROVIDERS (buildCatalog fails
 * otherwise). `label` is the envApiKeyAuth display name,
 * `envKey` its environment variable, `promptMessage` overrides the default
 * "Enter <label>" prompt text, `extraPrompts` adds non-key env prompts, and
 * `authless: true` marks OAuth-only providers whose API-key auth is out of
 * scope (models kept, auth omitted).
 */

/** Static pi providers deliberately excluded from the aletheia catalog. */
const EXCLUDED_PROVIDERS = new Set(["amazon-bedrock"]);

const PROVIDER_IDENTITY = {
	anthropic: { name: "Anthropic", label: "Anthropic API key", envKey: "ANTHROPIC_API_KEY" },
	"ant-ling": { name: "Ant Ling", label: "Ant Ling API key", envKey: "ANT_LING_API_KEY" },
	"azure-openai-responses": {
		name: "Azure OpenAI",
		label: "Azure OpenAI API key",
		envKey: "AZURE_OPENAI_API_KEY",
	},
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
	google: { name: "Google", label: "Gemini API key", envKey: "GEMINI_API_KEY" },
	"google-vertex": {
		name: "Google Vertex AI",
		label: "Google Cloud API key",
		envKey: "GOOGLE_CLOUD_API_KEY",
		promptMessage: "Enter Google Cloud API key",
		extraPrompts: [VERTEX_PROJECT, VERTEX_LOCATION],
	},
	groq: { name: "Groq", label: "Groq API key", envKey: "GROQ_API_KEY" },
	huggingface: { name: "Hugging Face", label: "Hugging Face token", envKey: "HF_TOKEN" },
	"kimi-coding": { name: "Kimi For Coding", label: "Kimi API key", envKey: "KIMI_API_KEY" },
	minimax: { name: "MiniMax", label: "MiniMax API key", envKey: "MINIMAX_API_KEY" },
	"minimax-cn": { name: "MiniMax CN", label: "MiniMax CN API key", envKey: "MINIMAX_CN_API_KEY" },
	mistral: { name: "Mistral", label: "Mistral API key", envKey: "MISTRAL_API_KEY" },
	moonshotai: { name: "Moonshot AI", label: "Moonshot AI API key", envKey: "MOONSHOT_API_KEY" },
	"moonshotai-cn": { name: "Moonshot AI CN", label: "Moonshot AI API key", envKey: "MOONSHOT_API_KEY" },
	nvidia: { name: "NVIDIA", label: "NVIDIA API key", envKey: "NVIDIA_API_KEY" },
	openai: { name: "OpenAI", label: "OpenAI API key", envKey: "OPENAI_API_KEY" },
	"openai-codex": { name: "OpenAI Codex", label: "OpenAI Codex token", envKey: "OPENAI_CODEX_TOKEN", authless: true },
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
	"vercel-ai-gateway": {
		name: "Vercel AI Gateway",
		label: "Vercel AI Gateway API key",
		envKey: "AI_GATEWAY_API_KEY",
	},
	xai: { name: "xAI", label: "xAI API key", envKey: "XAI_API_KEY" },
	xiaomi: { name: "Xiaomi", label: "Xiaomi API key", envKey: "XIAOMI_API_KEY" },
	"xiaomi-token-plan-ams": { name: "Xiaomi Token Plan AMS", label: "Xiaomi Token Plan AMS API key", envKey: "XIAOMI_TOKEN_PLAN_AMS_API_KEY" },
	"xiaomi-token-plan-cn": { name: "Xiaomi Token Plan CN", label: "Xiaomi Token Plan CN API key", envKey: "XIAOMI_TOKEN_PLAN_CN_API_KEY" },
	"xiaomi-token-plan-sgp": { name: "Xiaomi Token Plan SGP", label: "Xiaomi Token Plan SGP API key", envKey: "XIAOMI_TOKEN_PLAN_SGP_API_KEY" },
	zai: { name: "Z.AI", label: "Z.AI API key", envKey: "ZAI_API_KEY" },
	"zai-coding-cn": { name: "Z.AI Coding CN", label: "Z.AI Coding CN API key", envKey: "ZAI_CODING_CN_API_KEY" },
};

/**
 * Builds the aletheia catalog from pi's generated models. Pure: takes pi's
 * models.json plus provenance and returns the catalog object. Every static
 * provider and every model API is kept; the identity map must exactly cover
 * the providers pi generated (38 at the time of writing) after the excluded
 * ones.
 */
function buildCatalog(piModels, { piRevision = null } = {}) {
	const providers = [];
	for (const [providerId, models] of Object.entries(piModels)) {
		if (EXCLUDED_PROVIDERS.has(providerId)) continue;
		if (!(providerId in PROVIDER_IDENTITY)) {
			throw new Error(`pi static provider '${providerId}' has no PROVIDER_IDENTITY entry`);
		}
	}
	for (const [providerId] of Object.entries(PROVIDER_IDENTITY)) {
		if (EXCLUDED_PROVIDERS.has(providerId)) {
			throw new Error(`PROVIDER_IDENTITY entry '${providerId}' is also in EXCLUDED_PROVIDERS`);
		}
		const models = Object.values(piModels[providerId] ?? {});
		if (models.length === 0) {
			throw new Error(`PROVIDER_IDENTITY entry '${providerId}' has no models in pi's generated catalog`);
		}
		const identity = PROVIDER_IDENTITY[providerId];
		const entry = {
			id: providerId,
			name: identity.name,
			// Providers whose models span multiple base URLs keep the first
			// model's URL here; each model always carries its own baseUrl.
			baseUrl: models[0].baseUrl,
			auth: identity.authless
				? null
				: {
						label: identity.label,
						prompts: [
							{
								envKey: identity.envKey,
								message: identity.promptMessage ?? `Enter ${identity.label}`,
								secret: true,
							},
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

const isCli = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isCli) {
	const catalog = buildCatalog(generatePiCatalog(), { piRevision: piGitRevision() });
	const totalModels = catalog.providers.reduce((sum, p) => sum + p.models.length, 0);
	const apiIds = new Set(catalog.providers.flatMap((p) => p.models.map((m) => m.api)));
	writeFileSync(output, JSON.stringify(catalog) + "\n");
	console.log(
		`Wrote ${output}: ${catalog.providers.length} providers, ${totalModels} models, APIs: ${[...apiIds].sort().join(", ")}`,
	);
}
