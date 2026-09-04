package works.resolve.pathfinder.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Material Symbols (Google Fonts, Outlined, 24dp, weight 400, fill 0) for the
 * composer actions, kept local to avoid the material-icons-extended
 * dependency for two small icons.
 */
internal object ComposerIcons {
    val Send: ImageVector by lazy {
        ImageVector.Builder(
            name = "send",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            symbolPath {
                moveTo(3f, 20f)
                verticalLineTo(4f)
                lineToRelative(19f, 8f)
                lineTo(3f, 20f)
                close()
                moveTo(5f, 17f)
                lineTo(16.85f, 12f)
                lineTo(5f, 7f)
                verticalLineToRelative(3.5f)
                lineTo(11f, 12f)
                lineTo(5f, 13.5f)
                verticalLineTo(17f)
                close()
                moveToRelative(0f, 0f)
                verticalLineTo(12f)
                verticalLineTo(7f)
                verticalLineToRelative(3.5f)
                verticalLineToRelative(3f)
                verticalLineTo(17f)
                close()
            }
        }.build()
    }

    val Stop: ImageVector by lazy {
        ImageVector.Builder(
            name = "stop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            symbolPath {
                moveTo(8f, 8f)
                verticalLineToRelative(8f)
                verticalLineTo(8f)
                close()
                moveTo(6f, 18f)
                verticalLineTo(6f)
                horizontalLineTo(18f)
                verticalLineTo(18f)
                horizontalLineTo(6f)
                close()
                moveTo(8f, 16f)
                horizontalLineToRelative(8f)
                verticalLineTo(8f)
                horizontalLineTo(8f)
                verticalLineToRelative(8f)
                close()
            }
        }.build()
    }
}

/** Path attributes emitted by fonts.gstatic.com's Compose vector generator. */
private fun ImageVector.Builder.symbolPath(
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
) {
    path(
        fill = SolidColor(Color.Black),
        fillAlpha = 1f,
        stroke = null,
        strokeAlpha = 1f,
        strokeLineWidth = 1f,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Bevel,
        strokeLineMiter = 1f,
        pathFillType = PathFillType.NonZero,
        pathBuilder = block
    )
}
