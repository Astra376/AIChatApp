package com.example.aichat.core.design

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.size
import com.github.yohannestz.iconsax_compose.iconsax.Iconsax

typealias AppIconGlyph = ImageVector

@Composable
fun AppIcon(
    icon: AppIconGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint
    )
}

object AppIcons {
    val back = Iconsax.Linear.ArrowLeft

    val homeOutline = Iconsax.Linear.Home
    val home = Iconsax.Bold.Home
    val homeNavOutline = Iconsax.Linear.HomeThree
    val homeNav = Iconsax.Bold.HomeThree

    val createOutline = Iconsax.Linear.AddCircle
    val create = Iconsax.Bold.AddCircle
    val createAction = Iconsax.Linear.Add

    val chatsOutline = Iconsax.Linear.MessagesTwo
    val chats = Iconsax.Bold.MessagesTwo

    val profileOutline = Iconsax.Linear.Profile
    val profile = Iconsax.Bold.Profile

    val search = Iconsax.Linear.SearchNormalOne
    val searchFilled = Iconsax.Bold.SearchNormalOne
    val activity = Iconsax.Linear.Notification
    val discoverOutline = Iconsax.Linear.Discover
    val discover = Iconsax.Bold.Discover
    val sparkle = Iconsax.Linear.MagicStar
    val clear = Iconsax.Linear.Eraser
    val public = Iconsax.Linear.Global
    val lock = Iconsax.Linear.LockOne
    val send = Iconsax.Linear.Send
    val memory = ImageVector.Builder("Brain", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.65f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 6f); curveTo(12f, 2f, 6f, 2f, 6f, 6f)
            curveTo(2f, 6f, 1f, 11f, 4f, 13f); curveTo(1f, 17f, 6f, 21f, 8f, 19f)
            curveTo(9f, 22f, 12f, 21f, 12f, 18f); lineTo(12f, 6f)
            curveTo(12f, 2f, 18f, 2f, 18f, 6f); curveTo(22f, 6f, 23f, 11f, 20f, 13f)
            curveTo(23f, 17f, 18f, 21f, 16f, 19f); curveTo(15f, 22f, 12f, 21f, 12f, 18f)
            moveTo(6f, 6f); curveTo(6f, 8f, 7f, 9f, 9f, 9f)
            moveTo(4f, 13f); curveTo(7f, 12f, 8f, 14f, 8f, 16f)
            moveTo(18f, 6f); curveTo(18f, 8f, 17f, 9f, 15f, 9f)
            moveTo(20f, 13f); curveTo(17f, 12f, 16f, 14f, 16f, 16f)
        }
    }.build()
    val categoryArrow = ImageVector.Builder("Category arrow", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f)
        }
    }.build()
    val categoryDown = Iconsax.Linear.ArrowDown
    val stop = Iconsax.Bold.Stop
    val forward = Iconsax.Linear.Forward
    val previous = Iconsax.Linear.ArrowLeft
    val next = Iconsax.Linear.ArrowRight

    val themeSystem = Iconsax.Linear.Monitor
    val themeDark = Iconsax.Linear.Moon
    val themeLight = Iconsax.Linear.SunOne

    val logout = Iconsax.Linear.LogoutCurve
    val edit = Iconsax.Linear.Edit
    val settings = Iconsax.Linear.SettingTwo
    val share = Iconsax.Linear.Share
    val close = Icons.Default.Close
    val refresh = Iconsax.Linear.Refresh
    val newChat = Iconsax.Linear.MessageAdd

    val created = Iconsax.Linear.GridFour
    val createdFilled = Iconsax.Bold.GridFour
    val liked = Iconsax.Linear.Heart
    val likedFilled = Iconsax.Bold.Heart
}
