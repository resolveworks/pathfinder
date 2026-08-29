package works.resolve.pathfinder.ai.utils

/**
 * pi's getPiUserAgent (packages/ai/src/utils/pi-user-agent.ts):
 * `pi (${platform} ${release}; ${arch})`.
 *
 * Divergences:
 * - The ai package is JDK-pure (no android.* imports), so
 *   Build.VERSION.RELEASE is unavailable. The platform string is the fixed
 *   "android"; the release is `os.version` (the Linux kernel version reported
 *   by the JVM, the closest JDK-available analog to the Android release) and
 *   the arch is `os.arch`, matching upstream's os.release()/os.arch() shape.
 * - The product token is `pathfinder`, not pi's `pi` (owner decision): the
 *   User-Agent identifies the client to every provider, and Pathfinder
 *   should not misattribute its traffic to pi — the same decision as the
 *   Codex `originator` values.
 */
internal fun getPiUserAgent(): String =
    "pathfinder (android ${System.getProperty("os.version")}; ${System.getProperty("os.arch")})"
