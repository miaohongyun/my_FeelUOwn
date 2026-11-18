package org.feeluown.mobile.provider.modeb

import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.KotlinProviderFactory
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies

/** Compile-time plugin entry for the Mode B router provider module. */
object ModeBProviderFactory : KotlinProviderFactory {
    override val providerId: String = ModeBMusicProvider.ID
    override val info: ProviderInfo = ModeBMusicProvider.INFO

    override fun create(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider {
        return ModeBMusicProvider(providerCredentials = dependencies.credentials)
    }
}
