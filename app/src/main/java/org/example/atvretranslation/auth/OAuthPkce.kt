package org.example.atvretranslation.auth

import android.net.Uri
import android.util.Base64
import org.example.atvretranslation.data.AnimeLibClient
import java.security.MessageDigest
import java.security.SecureRandom

data class OAuthAttempt(
    val authorizationUrl: String,
    val verifier: String,
    val state: String,
)

fun createOAuthAttempt(): OAuthAttempt {
    val verifier = randomUrlSafe(96)
    val state = randomUrlSafe(30)
    val challenge = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )
    val url = Uri.parse("https://auth.hentaicdn.org/auth/oauth/authorize").buildUpon()
        .appendQueryParameter("scope", "")
        .appendQueryParameter("client_id", "1")
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("redirect_uri", AnimeLibClient.OAUTH_REDIRECT)
        .appendQueryParameter("state", state)
        .appendQueryParameter("code_challenge", challenge)
        .appendQueryParameter("code_challenge_method", "S256")
        .appendQueryParameter("prompt", "consent")
        .appendQueryParameter("iframe", "false")
        .build()
        .toString()
    return OAuthAttempt(url, verifier, state)
}

private fun randomUrlSafe(byteCount: Int): String {
    val bytes = ByteArray(byteCount).also { SecureRandom().nextBytes(it) }
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
