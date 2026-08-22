package com.aletheia.data.di

import com.aletheia.data.credentials.AndroidKeyStoreAeadCipher
import com.aletheia.data.credentials.AeadCipher
import com.aletheia.data.credentials.CredentialStore
import com.aletheia.data.credentials.KeystoreCredentialStore
import com.aletheia.data.settings.DataStoreSettingsRepository
import com.aletheia.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindCredentialStore(impl: KeystoreCredentialStore): CredentialStore

    @Binds
    @Singleton
    abstract fun bindAeadCipher(impl: AndroidKeyStoreAeadCipher): AeadCipher
}
