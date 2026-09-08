package works.resolve.pathfinder.ui.chat

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * User-facing text held unresolved in UI state: ViewModels reference string
 * resources (with format args); composables resolve them via [asText].
 * Args carry no secrets beyond what the target string itself displays.
 */
data class UiString(@StringRes val res: Int, val args: List<Any> = emptyList())

@Composable
fun UiString.asText(): String = stringResource(res, *args.toTypedArray())
