package com.example.globaltranslation.data.di

import com.example.globaltranslation.core.provider.*
import com.example.globaltranslation.data.preferences.PhotoPreferences
import com.example.globaltranslation.data.preferences.SecureApiKeyStore
import com.example.globaltranslation.data.provider.DeepSeekTranslator
import com.example.globaltranslation.data.provider.MlKitPhotoRecognizer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PhotoBindings {
    @Binds abstract fun preferences(value: PhotoPreferences): TranslationPreferences
    @Binds abstract fun keys(value: SecureApiKeyStore): ApiKeyRepository
    @Binds abstract fun recognizer(value: MlKitPhotoRecognizer): PhotoTextRecognizer
}

@Module
@InstallIn(SingletonComponent::class)
object PhotoNetworkModule {
    @Provides @Singleton fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    @Provides @Singleton fun translator(client: OkHttpClient): PhotoTranslator = DeepSeekTranslator(client)
}
