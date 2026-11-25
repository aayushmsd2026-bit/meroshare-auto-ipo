package np.ipohelper.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import np.ipohelper.core.AppState
import np.ipohelper.core.PeopleJson
import np.ipohelper.core.Person
import java.io.File
import java.security.KeyStore
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saves the family's details and the app's memory on this phone only.
 * Everything is encrypted with a key held by Android's hardware-backed Keystore,
 * which never leaves the phone and can't be copied out of it.
 */
class Store(context: Context) {
    private val dir = context.filesDir
    private val peopleFile = File(dir, "people.enc")
    private val stateFile = File(dir, "state.enc")

    companion object {
        private const val KEY_ALIAS = "ipo-helper-data"
        /** Only one run (background or "Check now") at a time. */
        val lock = ReentrantLock()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun write(file: File, text: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Base64.encodeToString(data, Base64.NO_WRAP))
        tmp.renameTo(file)
    }

    private fun read(file: File): String? {
        if (!file.exists()) return null
        return runCatching {
            val data = Base64.decode(file.readText(), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    fun people(): List<Person> = PeopleJson.decode(read(peopleFile))
    fun savePeople(people: List<Person>) = write(peopleFile, PeopleJson.encode(people))

    fun state(): AppState = AppState.fromJson(read(stateFile))
    fun saveState(state: AppState) = write(stateFile, state.toJson().toString())
}
