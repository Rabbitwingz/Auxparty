package app.auxparty

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * This phone's identity on the relay. Generated once, stored in private app
 * storage, and excluded from backups (allowBackup=false), so it never leaves
 * the device except as a TLS-protected auth message.
 */
object Identity {

    data class Device(val id: String, val secret: String)

    private const val PREFS = "identity"
    private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    @Synchronized
    fun get(context: Context): Device {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("deviceId", null)
        val secret = prefs.getString("deviceSecret", null)
        if (id != null && secret != null) return Device(id, secret)

        val random = SecureRandom()
        val newId = (1..26).map { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] }.joinToString("")
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val newSecret = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        // commit(), not apply(): the relay claims this identity on first
        // connect, so it must be durable before it is ever used.
        prefs.edit().putString("deviceId", newId).putString("deviceSecret", newSecret).commit()
        return Device(newId, newSecret)
    }
}
