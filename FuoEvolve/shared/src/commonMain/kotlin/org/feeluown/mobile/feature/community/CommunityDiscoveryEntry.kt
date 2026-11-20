package org.feeluown.mobile.feature.community

import androidx.compose.runtime.compositionLocalOf

/**
 * 设置页「音源与账号」分类里通往社区音源发现中心的入口。
 * 由 AppNavHost 提供（只有平台注入了发现控制器时才非空），设置页读取；
 * 这样设置页不需要持有发现控制器的引用，也不改动其深层签名。
 */
val LocalCommunitySourcesEntry = compositionLocalOf<(() -> Unit)?> { null }
