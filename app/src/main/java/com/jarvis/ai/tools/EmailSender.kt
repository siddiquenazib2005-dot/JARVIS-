package com.jarvis.ai.tools

import android.content.Context
import com.jarvis.ai.data.local.SecureStore
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Real SMTP email sending, the MYRA-parity counterpart to the existing
 * intent-based compose flow in [DeviceActionPack.email].
 *
 * Design notes:
 * - Credentials live in [SecureStore] (Keystore-backed AES/GCM), never in
 *   source and never in SharedPreferences plaintext.
 * - [send] NEVER throws. Every failure is returned as a typed [Outcome] so
 *   the chat layer can fall back to the compose-intent path instead of
 *   crashing or silently dropping the request.
 * - Gmail and most providers reject a normal account password over SMTP.
 *   The password slot expects an app-specific password; the error mapper
 *   below says so explicitly instead of printing a raw provider string.
 */
class EmailSender(context: Context) {

    private val store = SecureStore(context.applicationContext)

    /** Typed result so callers can distinguish "not set up" from "tried and failed". */
    sealed class Outcome {
        data class Sent(val to: String) : Outcome()
        /** SMTP credentials are missing; caller should fall back to compose intent. */
        object NotConfigured : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** True when enough credentials exist to attempt a direct send. */
    fun isConfigured(): Boolean =
        !user().isNullOrBlank() && !password().isNullOrBlank()

    /** The sending address, for UI copy. Null when unconfigured. */
    fun account(): String? = from() ?: user()

    /**
     * Sends a mail over SMTP. Blocking network work: call from an IO context.
     */
    fun send(to: String, subject: String, body: String): Outcome {
        val recipient = to.trim()
        if (recipient.isBlank() || !recipient.contains("@")) {
            return Outcome.Failed("that does not look like an email address")
        }
        val username = user()
        val secret = password()
        if (username.isNullOrBlank() || secret.isNullOrBlank()) return Outcome.NotConfigured

        val host = host()
        val port = port()
        val sender = from() ?: username

        return runCatching {
            val props = Properties().apply {
                put("mail.smtp.host", host)
                put("mail.smtp.port", port.toString())
                put("mail.smtp.auth", "true")
                // Port 465 is implicit TLS; everything else negotiates STARTTLS.
                if (port == SMTPS_PORT) {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.socketFactory.port", port.toString())
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                } else {
                    put("mail.smtp.starttls.enable", "true")
                }
                put("mail.smtp.connectiontimeout", TIMEOUT_MS.toString())
                put("mail.smtp.timeout", TIMEOUT_MS.toString())
                put("mail.smtp.writetimeout", TIMEOUT_MS.toString())
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(username, secret)
            })

            val mime = MimeMessage(session).apply {
                setFrom(InternetAddress(sender))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient))
                setSubject(if (subject.isBlank()) DEFAULT_SUBJECT else subject)
                setText(body)
            }
            Transport.send(mime)
            Outcome.Sent(recipient) as Outcome
        }.getOrElse { error -> Outcome.Failed(explain(error)) }
    }

    /**
     * Turns a provider exception into something a human can act on. Raw
     * JavaMail messages leak host names and occasionally the username, and
     * they never tell the user WHAT to fix.
     */
    private fun explain(error: Throwable): String {
        val raw = (error.message ?: error::class.java.simpleName).lowercase()
        return when {
            raw.contains("authentication") || raw.contains("535") || raw.contains("username and password") ->
                "the mail server rejected the login - most providers need an app-specific password, not the account password"
            raw.contains("unknownhost") || raw.contains("unable to resolve") ->
                "could not reach the mail server - check the SMTP host and your connection"
            raw.contains("timed out") || raw.contains("timeout") ->
                "the mail server did not respond in time"
            raw.contains("ssl") || raw.contains("handshake") ->
                "the secure connection failed - check the SMTP port (587 or 465)"
            raw.contains("relay") || raw.contains("550") ->
                "the server refused to relay this message to that address"
            else -> "the mail server refused the message"
        }
    }

    private fun user(): String? = store.get(KEY_USER)?.trim()
    private fun password(): String? = store.get(KEY_PASSWORD)
    private fun from(): String? = store.get(KEY_FROM)?.trim()?.ifBlank { null }
    private fun host(): String = store.get(KEY_HOST)?.trim()?.ifBlank { null } ?: DEFAULT_HOST

    private fun port(): Int =
        store.get(KEY_PORT)?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT

    companion object {
        /** SecureStore slots. Fill these from the settings sheet or adb. */
        const val KEY_HOST = "SMTP_HOST"
        const val KEY_PORT = "SMTP_PORT"
        const val KEY_USER = "SMTP_USER"
        const val KEY_PASSWORD = "SMTP_PASSWORD"
        const val KEY_FROM = "SMTP_FROM"

        const val DEFAULT_HOST = "smtp.gmail.com"
        const val DEFAULT_PORT = 587
        const val SMTPS_PORT = 465
        const val DEFAULT_SUBJECT = "Message from AURIX"

        private const val TIMEOUT_MS = 20_000

        /** Shown when the user asks to send mail before setting credentials. */
        const val SETUP_HINT =
            "Direct send is not set up yet, sir - add SMTP_USER and SMTP_PASSWORD in secrets to send without opening an app."
    }
}
