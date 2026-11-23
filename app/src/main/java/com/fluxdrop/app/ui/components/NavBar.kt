package com.fluxdrop.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxdrop.app.ui.theme.Flux

private data class NavItem(val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem("雷达", Icons.Rounded.TrackChanges),
    NavItem("传输", Icons.Rounded.SwapVert),
    NavItem("热点", Icons.Rounded.WifiTethering),
    NavItem("电脑", Icons.Rounded.Computer),
)

/** 悬浮胶囊导航栏，选中指示器带弹簧滑动 */
@Composable
fun FloatingNavBar(selected: Int, onSelect: (Int) -> Unit, badge: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    BoxWithConstraints(
        modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(70.dp)
            .shadow(28.dp, shape, ambientColor = Flux.Violet, spotColor = Flux.Violet)
            .background(Color(0xF0101731), shape)
            .border(1.dp, Flux.GlassBorder, shape)
            .padding(6.dp),
    ) {
        val itemW = maxWidth / navItems.size
        val x by animateDpAsState(
            itemW * selected,
            spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "navX",
        )
        val pill = RoundedCornerShape(24.dp)
        Box(
            Modifier
                .offset(x = x)
                .width(itemW)
                .fillMaxHeight()
                .clip(pill)
                .background(Brush.horizontalGradient(listOf(Flux.Cyan.copy(alpha = 0.26f), Flux.Violet.copy(alpha = 0.38f))))
                .border(1.dp, Flux.Cyan.copy(alpha = 0.35f), pill),
        )
        Row(Modifier.fillMaxSize()) {
            navItems.forEachIndexed { i, item ->
                val sel = i == selected
                val tint by animateColorAsState(if (sel) Color.White else Flux.TextLow, label = "tint")
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pill)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(23.dp))
                        if (i == 1 && badge) {
                            PulseDot(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-2).dp),
                                color = Flux.Mint,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        color = tint,
                        fontSize = 11.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
