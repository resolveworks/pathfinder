package works.resolve.pathfinder.ai.utils

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The format checks pi-ai passes to `compile()` (`packages/schema/src/formats`).
 *
 * This is a subset: the IDN/IRI formats (`idn-email`, `idn-hostname`, `iri`,
 * `iri-reference`) need TypeBox's Unicode tables and are omitted, so those
 * `format` names stay annotations instead of checks. The remaining checks
 * cover the common 2020-12 formats; `email`, `hostname`, `uri` and
 * `uri-reference` use simpler rules than TypeBox's full grammars.
 */
object SchemaFormats {
    val checks: Map<String, (String) -> Boolean> = mapOf(
        "date" to ::isDate,
        "date-time" to ::isDateTime,
        "duration" to { DURATION.matches(it) },
        "email" to { EMAIL.matches(it) },
        "hostname" to ::isHostname,
        "ipv4" to { IPV4.matches(it) },
        "ipv6" to { IPV6.matches(it) },
        "json-pointer" to { JSON_POINTER.matches(it) },
        "json-pointer-uri-fragment" to { JSON_POINTER_URI_FRAGMENT.matches(it) },
        "regex" to ::isRegex,
        "relative-json-pointer" to { RELATIVE_JSON_POINTER.matches(it) },
        "time" to ::isTime,
        "uri" to ::isUri,
        "uri-reference" to ::isUriReference,
        "uri-template" to { URI_TEMPLATE.matches(it) },
        "url" to { it.toHttpUrlOrNull() != null },
        "uuid" to { UUID.matches(it) }
    )

    private fun isDate(value: String): Boolean {
        val match = DATE.matchEntire(value) ?: return false
        val (year, month, day) = match.destructured
        val y = year.toInt()
        val m = month.toInt()
        val d = day.toInt()
        val leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
        return m in 1..12 && d >= 1 && d <= (if (m == 2 && leap) 29 else DAYS[m])
    }

    private fun isDateTime(value: String): Boolean {
        val parts = value.split(Regex("[Tt]"))
        return parts.size == 2 && isDate(parts[0]) && isTime(parts[1])
    }

    private fun isTime(value: String): Boolean {
        val match = TIME.matchEntire(value) ?: return false
        if (match.groupValues[4].isEmpty() && match.groupValues[5].isEmpty()) return false
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        val second = match.groupValues[3].toInt()
        if (hour > 23 || minute > 59 || second > 60) return false
        if (match.groupValues[5].isNotEmpty()) {
            if (match.groupValues[6].toInt() > 23 || match.groupValues[7].toInt() > 59) return false
        }
        if (second < 60) return true
        val sign = if (match.groupValues[5] == "-") -1 else 1
        val offsetHours = match.groupValues[6].ifEmpty { "0" }.toInt()
        val offsetMinutes = match.groupValues[7].ifEmpty { "0" }.toInt()
        val utcMinutes = hour * 60 + minute - sign * (offsetHours * 60 + offsetMinutes)
        return ((utcMinutes % 1440) + 1440) % 1440 == 1439
    }

    private fun isHostname(value: String): Boolean =
        value.length <= 253 && value.split(".").all { HOSTNAME_LABEL.matches(it) }

    private fun isUri(value: String): Boolean = try {
        URI(value).isAbsolute
    } catch (_: Exception) {
        false
    }

    private fun isUriReference(value: String): Boolean = try {
        URI(value)
        true
    } catch (_: Exception) {
        false
    }

    private fun isRegex(value: String): Boolean = try {
        Regex(value)
        true
    } catch (_: Exception) {
        false
    }

    private fun regex(source: String, ignoreCase: Boolean = false): Regex = Regex(
        source.trimIndent().replace("\n", ""),
        if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    )

    private val DAYS = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    private val DATE = Regex("""^(\d\d\d\d)-(\d\d)-(\d\d)$""")
    private val TIME = Regex(
        """^(\d\d):(\d\d):(\d\d)(?:\.\d+)?(?:([Zz])|([+-])(\d\d):(\d\d))?$"""
    )
    private val DURATION = regex(
        """
        ^P((\d+Y(\d+M(\d+D)?)?|\d+M(\d+D)?|\d+D)
        (T(\d+H(\d+M(\d+S)?)?|\d+M(\d+S)?|\d+S))?
        |T(\d+H(\d+M(\d+S)?)?|\d+M(\d+S)?|\d+S)
        |\d+W)$
        """
    )
    private val EMAIL = Regex("""^(?:[^@\s]+)@(?:[^@\s]+)$""")
    private val HOSTNAME_LABEL = Regex("""^(?!-)[a-z0-9-]{1,63}(?<!-)$""", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex(
        """^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$"""
    )
    private val IPV6 = regex(
        """
        ^(?:(?:(?:[0-9a-f]{1,4}:){6}|::(?:[0-9a-f]{1,4}:){5}|
        (?:[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){4}|
        (?:(?:[0-9a-f]{1,4}:)?[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){3}|
        (?:(?:[0-9a-f]{1,4}:){0,2}[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){2}|
        (?:(?:[0-9a-f]{1,4}:){0,3}[0-9a-f]{1,4})?::[0-9a-f]{1,4}:|
        (?:(?:[0-9a-f]{1,4}:){0,4}[0-9a-f]{1,4})?::)
        (?:[0-9a-f]{1,4}:[0-9a-f]{1,4}|
        (?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}
        (?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|
        (?:(?:[0-9a-f]{1,4}:){0,5}[0-9a-f]{1,4})?::[0-9a-f]{1,4}|
        (?:(?:[0-9a-f]{1,4}:){0,6}[0-9a-f]{1,4})?::)$
        """,
        ignoreCase = true
    )
    private val JSON_POINTER = Regex("""^(?:/(?:[^~/]|~0|~1)*)*$""")
    private val JSON_POINTER_URI_FRAGMENT = Regex(
        """^#(?:/(?:[a-z0-9_\-.!$&'()*+,;:=@]|%[0-9a-f]{2}|~0|~1)*)*$""",
        RegexOption.IGNORE_CASE
    )
    private val RELATIVE_JSON_POINTER = Regex("""^(?:0|[1-9][0-9]*)(?:#|(?:/(?:[^~/]|~0|~1)*)*)$""")
    private val URI_TEMPLATE = regex(
        """
        ^(?:(?:[^\x00-\x20"<>%\\^`{|}\x7f]|%[0-9a-f]{2})|
        \{[+#./;?&=,!@|]?(?:[a-z0-9_]|%[0-9a-f]{2})+
        (?:\.(?:[a-z0-9_]|%[0-9a-f]{2})+)*
        (?::[1-9]\d{0,3}|\*)?
        (?:,(?:[a-z0-9_]|%[0-9a-f]{2})+
        (?:\.(?:[a-z0-9_]|%[0-9a-f]{2})+)*
        (?::[1-9]\d{0,3}|\*)?)*\})*$
        """,
        ignoreCase = true
    )
    private val UUID = Regex(
        """^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$""",
        RegexOption.IGNORE_CASE
    )
}
