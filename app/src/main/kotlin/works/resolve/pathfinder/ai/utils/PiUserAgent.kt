package works.resolve.pathfinder.ai.utils

/**
 * pi's getPiUserAgent (packages/ai/src/utils/pi-user-agent.ts):
 * `pi (${platform} ${release}; ${arch})`.
 *
 * Divergence: the ai package is JDK-pure (no android.* imports), so
 * Build.VERSION.RELEASE is unavailable. The platform string is the fixed
 * "android"; the release is `os.version` (the Linux kernel version reported
 * by the JVM, the closest JDK-available analog to the Android release) and
 * the arch is `os.arch`, matching upstream's os.release()/os.arch() shape.
 */
internal fun getPiUserAgent(): String =
    "pi (android ${System.getProperty("os.version")}; ${System.getProperty("os.arch")})"
