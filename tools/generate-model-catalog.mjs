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

const API = "openai-completions";

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
const PROVIDER_IDENTITY = {
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
 * Builds the aletheia catalog from pi's generated models. Pure: takes pi's
 * models.json plus provenance and returns the catalog object.
 */
function buildCatalog(piModels, { piRevision = null } = {}) {
	const providers = [];

	for (const [providerId, identity] of Object.entries(PROVIDER_IDENTITY)) {
		const models = Object.values(piModels[providerId] ?? {}).filter((model) => model.api === API);
		const entry = {
			id: providerId,
			name: identity.name,
			baseUrl: models[0].baseUrl,
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

const isCli = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isCli) {
	const catalog = buildCatalog(generatePiCatalog(), { piRevision: piGitRevision() });
	const totalModels = catalog.providers.reduce((sum, p) => sum + p.models.length, 0);
	writeFileSync(output, JSON.stringify(catalog) + "\n");
	console.log(`Wrote ${output}: ${catalog.providers.length} providers, ${totalModels} ${API} models`);
}
