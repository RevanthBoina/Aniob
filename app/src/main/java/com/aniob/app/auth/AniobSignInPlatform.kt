package com.aniob.app.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.aniob.app.MainActivity
import com.aniob.app.network.AniobHttpClientSingleton
import com.aniob.core.auth.AniobAuthConfig
import com.aniob.core.auth.AniobAuthError
import com.aniob.core.auth.AniobAuthRepository
import com.aniob.core.auth.AniobBrowserLauncher
import com.aniob.core.auth.AniobCallbackListener
import com.aniob.core.auth.AniobClock
import com.aniob.core.auth.AniobHttpReply
import com.aniob.core.auth.AniobPendingSignInStore
import com.aniob.core.auth.AniobSignInFlow
import com.aniob.core.auth.AniobTokenTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Suspends until the response headers arrive; cancelling the coroutine cancels the call. */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
    })
}

/** Token-endpoint transport over the app's shared OkHttp client (its pool, timeouts and TLS). */
class AniobOkHttpTokenTransport(
    private val client: OkHttpClient = AniobHttpClientSingleton.client
) : AniobTokenTransport {
    override suspend fun post(url: String, contentType: String, body: String): AniobHttpReply {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(body.toRequestBody(contentType.toMediaType()))
            .build()
        val response = client.newCall(request).awaitResponse()
        return withContext(Dispatchers.IO) {
            response.use {
                // A token reply is tiny; cap what is ever read so a hostile host cannot flood memory.
                AniobHttpReply(it.code, it.header("Content-Type"), it.peekBody(MAX_REPLY_BYTES).string())
            }
        }
    }

    private companion object {
        const val MAX_REPLY_BYTES = 1L * 1024 * 1024
    }
}

/** Opens the authorize URL in a Custom Tab (the user's browser), never in an embedded web view. */
class AniobCustomTabBrowserLauncher(private val context: Context) : AniobBrowserLauncher {
    override fun open(url: String): Boolean {
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            tab.launchUrl(context, Uri.parse(url))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}

/**
 * Starts, cancels and resumes the sign-in. Single flight: starting while one is running cancels
 * the first and waits for it to release the port before binding again.
 */
class AniobSignInCoordinator(
    private val context: Context,
    private val config: AniobAuthConfig,
    private val repository: AniobAuthRepository,
    private val pendingStore: AniobPendingSignInStore,
    private val scope: CoroutineScope,
    private val clock: AniobClock = AniobClock.SYSTEM,
    private val listenerFactory: () -> AniobCallbackListener = { AniobLoopbackCallbackListener(config.redirectPort) },
    private val browser: AniobBrowserLauncher = AniobCustomTabBrowserLauncher(context),
    private val onSignedIn: () -> Unit = { bringAppToFront(context) }
) {
    private val flow = AniobSignInFlow(config, repository, listenerFactory, browser, pendingStore, clock)
    private val lock = Any()
    private var job: Job? = null

    init {
        AniobSignInService.onCancelRequested = { cancel() }
    }

    fun start() = launch(resume = false)

    /** Called when the account screen opens: continue a sign-in the system interrupted. */
    fun resumeIfPending() = launch(resume = true)

    fun cancel() {
        synchronized(lock) { job?.cancel() }
        AniobSignInService.stop(context)
    }

    private fun launch(resume: Boolean) = synchronized(lock) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            val pending = if (resume) pendingStore.load() ?: return@launch else null
            AniobSignInService.start(context)
            try {
                val error: AniobAuthError? = flow.run(resume = pending)
                if (error == null) onSignedIn()
            } finally {
                AniobSignInService.stop(context)
            }
        }
    }

    companion object {
        /**
         * Best effort. Android may refuse a background activity start; the success page also links
         * back to the app through its deep link, so the user always has a way back.
         */
        fun bringAppToFront(context: Context): Boolean {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            return try {
                context.startActivity(intent)
                true
            } catch (e: ActivityNotFoundException) {
                false // nothing to bring forward
            } catch (e: SecurityException) {
                false // the platform blocked a background launch; the page's deep link covers it
            }
        }
    }
}
