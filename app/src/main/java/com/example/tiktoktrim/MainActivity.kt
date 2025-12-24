package com.example.tiktoktrim

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TikTokTrim"

        // Single OkHttp client instance with reasonable timeouts
        private val httpClient: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate called with action: ${intent?.action}")

        // Handle both VIEW (deep links) and SEND (share) intents
        val incomingUri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> {
                Log.d(TAG, "ACTION_VIEW with data: ${intent?.data}")
                intent?.data
            }
            Intent.ACTION_SEND -> {
                // When sharing text (e.g. from other apps), try to extract the first URL-like substring
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                Log.d(TAG, "ACTION_SEND with text: $sharedText")
                extractFirstUrl(sharedText)?.let { Uri.parse(it) }
            }
            else -> {
                Log.d(TAG, "Unknown action, trying to get data anyway")
                intent?.data
            }
        }

        if (incomingUri == null) {
            Log.d(TAG, "No URI found, finishing activity")
            finish()
            return
        }

        Log.d(TAG, "Processing URI: $incomingUri")

        // Launch coroutine to do network resolution off the main thread
        lifecycleScope.launch {
            try {
                handleIncomingUri(incomingUri)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling URI", e)
            } finally {
                // Ensure activity closes even if something goes wrong
                finish()
            }
        }
    }

    private suspend fun handleIncomingUri(uri: Uri) {
        val originalUrl = uri.toString()
        Log.d(TAG, "Original URL: $originalUrl")

        // If the URI is a TikTok short link (vm.tiktok.com) resolve the redirect to the final URL
        val finalUrl = if ((uri.host ?: "").equals("vm.tiktok.com", ignoreCase = true)) {
            Log.d(TAG, "Detected vm.tiktok.com, resolving redirect...")
            val resolved = resolveRedirect(originalUrl)
            Log.d(TAG, "Resolved to: $resolved")
            resolved ?: originalUrl
        } else {
            originalUrl
        }

        // Check if we landed on a login page and extract the actual video URL
        val actualUrl = extractFromLoginPage(finalUrl)
        Log.d(TAG, "After login extraction: $actualUrl")

        val cleaned = trimTikTokUrl(actualUrl)
        Log.d(TAG, "Cleaned URL: $cleaned")

        // Opening the browser must run on the main thread
        withContext(Dispatchers.Main) {
            openInBrowser(cleaned)
        }
    }

    /**
     * If the URL is a TikTok login page, extract the redirect_url parameter.
     * Otherwise return the URL as-is.
     */
    private fun extractFromLoginPage(url: String): String {
        return try {
            val uri = Uri.parse(url)

            // Check if this is a TikTok login page
            if (uri.host?.contains("tiktok.com") == true &&
                uri.path?.contains("login") == true) {

                Log.d(TAG, "Detected login page, extracting redirect_url")

                // Extract and decode the redirect_url parameter
                uri.getQueryParameter("redirect_url")?.let { redirectUrl ->
                    // URL decode the parameter value
                    val decoded = Uri.decode(redirectUrl)
                    Log.d(TAG, "Extracted redirect URL: $decoded")
                    decoded
                } ?: url
            } else {
                url
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error extracting from login page", t)
            // If parsing fails, return original
            url
        }
    }

    /**
     * Remove tracking parameters (_t) while keeping other query parameters.
     * Also removes fragments.
     */
    private fun trimTikTokUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val builder = uri.buildUpon().clearQuery()

            // Rebuild query string without _t parameter
            uri.queryParameterNames.forEach { paramName ->
                if (paramName != "_t") {
                    // Keep all parameters except _t
                    uri.getQueryParameter(paramName)?.let { paramValue ->
                        builder.appendQueryParameter(paramName, paramValue)
                    }
                }
            }

            builder.fragment(null).build().toString()
        } catch (t: Throwable) {
            Log.e(TAG, "Error trimming URL", t)
            // If parsing fails, return original
            url
        }
    }

    /**
     * For a short-link (vm.tiktok.com) perform a network request to follow redirects and
     * return the final destination URL. Runs on IO dispatcher where called.
     */
    private suspend fun resolveRedirect(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .get()
                .build()

            httpClient.newCall(request).execute().use { resp ->
                // After following redirects OkHttp's request.url is the final URL
                val finalUrl = resp.request.url.toString()
                Log.d(TAG, "HTTP response code: ${resp.code}, final URL: $finalUrl")
                finalUrl
            }
        }.onFailure { e ->
            Log.e(TAG, "Error resolving redirect", e)
        }.getOrNull()
    }

    /**
     * Try to open the URL in the user's browser.
     * We explicitly try common browsers to avoid looping back to ourselves.
     */
    private fun openInBrowser(url: String) {
        try {
            val uri = Uri.parse(url)

            // 1) Try a plain ACTION_VIEW for the actual URL. If there's any browser, this should work.
            val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            val handlers = packageManager.queryIntentActivities(viewIntent, 0)
            Log.d(TAG, "Found ${handlers.size} handlers for the URL")
            handlers.forEach { Log.d(TAG, "Handler: ${it.activityInfo.packageName}/${it.activityInfo.name}") }

            // Filter out our own package to avoid loops
            val externalHandlers = handlers.filter { it.activityInfo.packageName != packageName }
            if (externalHandlers.isNotEmpty()) {
                // Prefer Chrome if present among handlers; otherwise pick the first
                val preferred = externalHandlers.firstOrNull { it.activityInfo.packageName in preferredBrowserPackages } ?: externalHandlers.first()
                Log.d(TAG, "Launching with: ${preferred.activityInfo.packageName}")
                viewIntent.setPackage(preferred.activityInfo.packageName)
                startActivity(viewIntent)
                return
            }

            // 2) If no handlers are returned (rare), explicitly probe known browser packages
            val installedKnown = preferredBrowserPackages.filter { pkg ->
                runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
            }

            if (installedKnown.isNotEmpty()) {
                val browserPackage = installedKnown.first()
                Log.d(TAG, "No generic handlers; falling back to known browser: $browserPackage")
                val explicit = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(browserPackage)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(explicit)
                return
            }

            // 3) Last resort: try without setting package and let system decide
            try {
                Log.d(TAG, "Trying ACTION_VIEW without explicit package")
                startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "ERROR: No browser found! Install Chrome or another browser in the emulator.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening browser", e)
        }
    }

    private val preferredBrowserPackages = listOf(
        // Common Chrome package names
        "com.android.chrome",
        "com.google.android.apps.chrome",
        // Other popular browsers
        "org.mozilla.firefox",
        "com.microsoft.emmx", // Edge
        "com.sec.android.app.sbrowser", // Samsung Internet
        "com.opera.browser",
        "com.brave.browser"
    )

    /**
     * Basic helper to pull the first http/https substring from shared text.
     * This is intentionally small and permissive (not a full URL parser).
     */
    private fun extractFirstUrl(text: String): String? {
        val regex = "(https?://[^\\s]+)".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(text)?.value
    }
}