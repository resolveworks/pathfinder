package works.resolve.pathfinder.ai.utils

/**
 * User-Agent sent with provider requests. Divergences from pi's
 * `pi (${platform} ${release}; ${arch})`:
 * - The ai package is JDK-pure, so Build.VERSION.RELEASE is unavailable and
 *   the platform is the fixed "android"; the release is `os.version`, the
 *   Linux kernel version reported by the JVM.
 * - The product token is `pathfinder`, not pi's `pi`: the User-Agent
 *   identifies the client to every provider, and Pathfinder should not
 *   misattribute its traffic to pi.
 */
internal fun getPiUserAgent(): String =
    "pathfinder (android ${System.getProperty("os.version")}; ${System.getProperty("os.arch")})"
