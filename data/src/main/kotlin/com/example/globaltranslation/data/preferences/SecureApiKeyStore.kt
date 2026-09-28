package com.example.globaltranslation.data.preferences

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.example.globaltranslation.core.model.ApiKeyStatus
import com.example.globaltranslation.core.provider.ApiKeyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** The ciphertext lives in noBackupFilesDir; plaintext is never persisted or exposed in UI state. */
@Singleton
class SecureApiKeyStore @Inject constructor(@ApplicationContext context: Context) : ApiKeyRepository {
    private val file = AtomicFile(File(context.noBackupFilesDir, "deepseek-key.enc"))
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(ApiKeyStatus(file.baseFile.exists()))
    override val status = mutableStatus.asStateFlow()

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override suspend fun read(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.baseFile.exists()) return@withLock ""
            val bytes = file.readFully()
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }

    override suspend fun save(value: String) = withContext(Dispatchers.IO) {
        val normalized = value.trim()
        require(normalized.isNotEmpty() && normalized.length <= 512 && normalized.all { it.code in 33..126 })
        mutex.withLock {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            val bytes = cipher.iv + cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
            mutableStatus.value = ApiKeyStatus(true, mutableStatus.value.revision + 1)
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete()
            mutableStatus.value = ApiKeyStatus(false, mutableStatus.value.revision + 1)
        }
    }

    private companion object { const val ALIAS = "globaltranslation.deepseek.v1" }
}
