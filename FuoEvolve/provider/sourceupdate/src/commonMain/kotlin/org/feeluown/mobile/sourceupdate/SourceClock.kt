package org.feeluown.mobile.sourceupdate

/**
 * 音源更新使用的墙上时钟（毫秒）。
 *
 * 单独抽出来而不是直接调 `System.currentTimeMillis()`：iOS 与 Android 的实现不同，
 * 而且所有退避/间隔判断都接受注入时钟，测试里才能用固定时间验证 1h/4h/12h/24h 的节奏。
 */
expect fun sourceUpdateNowMillis(): Long
