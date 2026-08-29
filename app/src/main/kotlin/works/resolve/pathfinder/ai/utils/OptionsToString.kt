package works.resolve.pathfinder.ai.utils

/**
 * Redacted `toString()` support for provider options classes (TS→Kotlin
 * translation conventions: Options and redaction). Field values must never
 * be passed directly — secrets via [redactedSecret], maps as `.keys`, hooks
 * as `hook != null`. A redaction compiler plugin was deliberately rejected:
 * it cannot express keys-only redaction and adds a compiler-plugin
 * dependency.
 */

/** Renders `"null"` or `"<redacted>"` — never the secret itself. */
internal fun redactedSecret(secret: String?): String = if (secret == null) "null" else "<redacted>"

/** Joins already-redacted field values into the standard `Class(a=…, b=…)` form. */
internal fun optionsToString(className: String, vararg fields: Pair<String, Any?>): String =
    className + fields.joinToString(prefix = "(", separator = ", ", postfix = ")") { (name, value) ->
        "$name=$value"
    }
