/**
 * Utility class for integrating with the Percipia Nexus hospitality platform.
 * Handles HTTP communication with the Nexus endpoint to fetch and cache guest extension parameters,
 * including restrictions for guest-to-guest calling, guest-to-admin messaging, and conversation access.
 *
 * @author Maj Kravos <https://www.majkravos.com>
 */
package org.linphone.utils

import androidx.annotation.WorkerThread
import okhttp3.FormBody
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.tools.Log
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier

class PercipiaNexus {
    companion object {
        private const val TAG = "[Percipia Nexus]"

        data class ConnectParams(
            val isGuest: Boolean,
            val isGuestToAdminMessagingEnabled: Boolean,
            val isGuestToGuestCallingEnabled: Boolean
        )

        data class CachedConnectParams(
            val params: ConnectParams,
            val timestamp: Long
        )

        // WARNING: Only enable for lab testing with self-signed certificates, do not use in prod
        private const val SKIP_SSL_VERIFICATION = true
        private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        private val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }

        private val client = OkHttpClient.Builder()
            .apply {
                if (SKIP_SSL_VERIFICATION) {
                    Log.w(TAG, "SSL certificate verification is DISABLED - only use for testing!")
                    sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                    hostnameVerifier(HostnameVerifier { _, _ -> true })
                }
            }
            .build()

        // Nexus server
        private const val ENDPOINT = "getConnectParams"
        private const val PORT = "8443"
        private const val CACHE_EXPIRY_MS = 60 * 1000 // 1 minute to account for Nexus rate limiting

        private val paramsCache = mutableMapOf<String, CachedConnectParams>()

        @WorkerThread
        private fun getPbxAddress(account: Account): String? {
            val domain = account.params.identityAddress?.domain
            // Strip port if present
            val strippedDomain = domain?.split(":")?.get(0) ?: return null
            
            return try {
                // Resolve domain to IP address via DNS lookup
                val inetAddress = InetAddress.getByName(strippedDomain)
                inetAddress.hostAddress // Returns the IP address as a string
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve domain [$strippedDomain]: ${e.message}")
                strippedDomain // Fall back to the original domain/IP
            }
        }

        private fun getPbxDomain(account: Account): String? {
            val domain = account.params.identityAddress?.domain
            // Strip port if present
            return domain?.split(":")?.get(0)
        }

        private fun getExtensionNumber(account: Account): String? {
            return account.params.identityAddress?.username
        }

        @WorkerThread
        private fun getConnectParams(account: Account): JSONObject? {
            val pbxAddress = getPbxAddress(account) ?: return null
            val domain = getPbxDomain(account) ?: return null
            val extension = getExtensionNumber(account) ?: return null
            
            val url = "https://$pbxAddress:$PORT/$ENDPOINT"
            
            val formBody = FormBody.Builder()
                .add("domain", domain)
                .add("extension", extension)
                .build()
            
            val request = okhttp3.Request.Builder()
                .url(url)
                .post(formBody)
                .build()
                
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch connect params: ${response.code}")
                return null
            }

            val responseBody = response.body?.string()
            if (responseBody == null) {
                Log.e(TAG, "Response body is null for extension [$extension]")
                return null
            }
            
            val responseJSON = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse JSON response for extension [$extension]: ${e.message}")
                return null
            }

            return responseJSON
        }

        private fun getAccountForExtension(extension: String): Account? {
            for (account in coreContext.core.accountList) {
                val address = account.params.identityAddress ?: continue
                if (address.username == extension) {
                    return account
                }
            }
            Log.e(TAG, "No account found for extension [$extension]")
            return null
        }

        private fun isCacheValid(cachedParams: CachedConnectParams): Boolean {
            return (System.currentTimeMillis() - cachedParams.timestamp) < CACHE_EXPIRY_MS
        }

        @WorkerThread
        private fun getConnectParamsForExtension(extension: String?): ConnectParams? {
            if (extension == null) {
                Log.e(TAG, "Extension is null")
                return null
            }

            // Check cache first
            val cached = paramsCache[extension]
            if (cached != null && isCacheValid(cached)) {
                Log.d(TAG, "Using cached connect params for extension [$extension]")
                return cached.params
            }

            val account = getAccountForExtension(extension) ?: return null
            val responseBody = getConnectParams(account) ?: return null

            val params = ConnectParams(
                isGuest = responseBody.getBoolean("is_guest_extension"),
                isGuestToAdminMessagingEnabled = responseBody.getBoolean("is_guest_to_admin_messaging_enabled"),
                isGuestToGuestCallingEnabled = responseBody.getBoolean("is_guest_to_guest_calling_enabled")
            )

            // Cache the result
            paramsCache[extension] = CachedConnectParams(params, System.currentTimeMillis())

            return params
        }

        @WorkerThread
        fun chatPageEnabledForExtension(extension: String?): Boolean {
            val params = getConnectParamsForExtension(extension)
            if (params != null && params.isGuest && !params.isGuestToAdminMessagingEnabled) {
                Log.i(TAG, "Guest without admin messaging rights - disabling conversations page")
                return false
            }
            return true
        }

        @WorkerThread
        fun outgoingChatAllowed(fromExtension: String?, toExtension: String?, isGroupChat: Boolean): Boolean {
            val fromExtensionParams = getConnectParamsForExtension(fromExtension)
            val toExtensionParams = getConnectParamsForExtension(toExtension)

            if (fromExtensionParams != null && toExtensionParams != null) {
                if (fromExtensionParams.isGuest && toExtensionParams.isGuest) {
                    Log.w("$TAG Guest extension [$fromExtension] is not allowed to message extension [$toExtension] because it is another guest extension")
                    return false
                } else if (fromExtensionParams.isGuest && isGroupChat) {
                    Log.w("$TAG Guest extension [$fromExtension] is not allowed to create group chats")
                    return false
                } else if (fromExtensionParams.isGuest && !fromExtensionParams.isGuestToAdminMessagingEnabled) {
                    Log.w("$TAG Guest extension [$fromExtension] is not allowed to message admin extension [$toExtension] because guest-to-admin messaging is disabled")
                    return false
                } else {
                    return true
                }
            } else {
                Log.w("$TAG fromExtensionParams or toExtensionParams is null, allowing outgoing message by default")
                return true
            }
        }

        @WorkerThread
        fun outgoingCallAllowed(fromExtension: String?, toExtension: String?): Boolean {
            val fromExtensionParams = getConnectParamsForExtension(fromExtension)
            val toExtensionParams = getConnectParamsForExtension(toExtension)

            if (fromExtensionParams != null && toExtensionParams != null) {
                if (fromExtensionParams.isGuest && toExtensionParams.isGuest && !fromExtensionParams.isGuestToGuestCallingEnabled) {
                    Log.w("$TAG Guest extension [$fromExtension] is not allowed to call extension [$toExtension] because guest-to-guest calling is disabled")
                    return false
                } else {
                    return true
                }
            } else {
                Log.w("$TAG fromExtensionParams or toExtensionParams is null, allowing outgoing call by default")
                return true
            }
        }

        fun getHttpClient(): OkHttpClient {
            return client
        }
    }
}
