package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.auth.CodexOAuth
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 凭据仅在请求发送前解析；401 恢复止于响应头，不重放已经开始的模型流。 */
internal object AuthorizedProviderHttp {
    fun <T> execute(
        client: OkHttpClient,
        providerId: String,
        authMode: String,
        request: Request,
        controller: AgentRunController,
        accessToken: (String, String, AgentRunController, String?) -> String =
            { id, mode, run, rejected -> ProviderCredentialRepository.accessToken(id, mode, run, rejected) },
        consume: (Response) -> T,
    ): T {
        val profile = ProviderConnectionProfile.forAuthMode(authMode)
        profile?.requireRequestTarget(request.url)
        val httpClient = if (profile == null) client else client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        var rejectedToken: String? = null
        var refreshed = false
        while (true) {
            controller.throwIfCancelled()
            val token = profile?.let { accessToken(providerId, authMode, controller, rejectedToken) }
            val authorized = if (token == null) request else request.newBuilder()
                .header("Authorization", "Bearer $token")
                .apply {
                    if (profile == ProviderConnectionProfile.OPENAI_CODEX) {
                        header("ChatGPT-Account-Id", CodexOAuth.accountId(token))
                    }
                }
                .build()
            val call = httpClient.newCall(authorized)
            val binding = controller.register(cancel = call::cancel)
            try {
                controller.throwIfCancelled()
                call.execute().use { response ->
                    controller.throwIfCancelled()
                    if (profile != null && response.code == 401 && !refreshed) {
                        rejectedToken = token
                        refreshed = true
                    } else {
                        if (profile != null && response.code == 401) {
                            val failure = AgentModelFailure.http(response.code, response.peekBody(16_384).string())
                            if (failure.code != "HTTP_401") throw failure
                            throw AgentModelFailure(
                                "OAUTH_UNAUTHORIZED", false, "订阅登录已失效，请重新登录服务商账户。",
                            )
                        }
                        return consume(response)
                    }
                }
            } catch (failure: Throwable) {
                controller.throwIfCancelled()
                throw failure
            } finally {
                binding.close()
            }
        }
    }

    /**
     * SSE 收集器需要自行持有 Call 生命周期，因此这里以 Call.Factory 形式提供同样的
     * 凭据注入与一次性 401 恢复；流一旦开始输出就不会被重放。
     */
    fun callFactory(
        client: OkHttpClient,
        providerId: String,
        authMode: String,
        controller: AgentRunController,
        accessToken: (String, String, AgentRunController, String?) -> String =
            { id, mode, run, rejected -> ProviderCredentialRepository.accessToken(id, mode, run, rejected) },
    ): Call.Factory {
        val profile = ProviderConnectionProfile.forAuthMode(authMode) ?: return client
        val httpClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        return Call.Factory { request ->
            AuthorizedCall(httpClient, profile, providerId, authMode, controller, request, accessToken)
        }
    }

    private class AuthorizedCall(
        private val client: OkHttpClient,
        private val profile: ProviderConnectionProfile,
        private val providerId: String,
        private val authMode: String,
        private val controller: AgentRunController,
        private val original: Request,
        private val accessToken: (String, String, AgentRunController, String?) -> String,
    ) : Call {
        private val lock = Any()
        private var current: Call? = null
        private var currentToken: String? = null
        private var executed = false
        private var canceled = false

        override fun request(): Request = synchronized(lock) { current?.request() } ?: original

        override fun execute(): Response {
            synchronized(lock) {
                check(!executed) { "Already Executed" }
                executed = true
            }
            controller.throwIfCancelled()
            var rejected: String? = null
            while (true) {
                val call = prepare(rejected)
                val binding = controller.register(cancel = call::cancel)
                try {
                    val response = call.execute()
                    if (response.code == 401 && rejected == null) {
                        rejected = currentToken
                        response.close()
                        continue
                    }
                    return response
                } finally {
                    binding.close()
                }
            }
        }

        override fun enqueue(responseCallback: Callback) {
            synchronized(lock) {
                check(!executed) { "Already Executed" }
                executed = true
            }
            enqueueAttempt(responseCallback, rejected = null)
        }

        private fun enqueueAttempt(responseCallback: Callback, rejected: String?) {
            controller.throwIfCancelled()
            val call = prepare(rejected)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = responseCallback.onFailure(call, e)

                override fun onResponse(call: Call, response: Response) {
                    if (response.code == 401 && rejected == null) {
                        response.close()
                        enqueueAttempt(responseCallback, currentToken)
                        return
                    }
                    responseCallback.onResponse(call, response)
                }
            })
        }

        private fun prepare(rejected: String?): Call {
            controller.throwIfCancelled()
            profile.requireRequestTarget(original.url)
            val token = accessToken(providerId, authMode, controller, rejected)
            val authorized = original.newBuilder()
                .header("Authorization", "Bearer $token")
                .apply {
                    if (profile == ProviderConnectionProfile.OPENAI_CODEX) {
                        header("ChatGPT-Account-Id", CodexOAuth.accountId(token))
                    }
                }
                .build()
            val call = client.newCall(authorized)
            synchronized(lock) {
                current = call
                currentToken = token
            }
            return call
        }

        override fun cancel() {
            synchronized(lock) {
                canceled = true
                current?.cancel()
            }
        }

        override fun isExecuted(): Boolean = synchronized(lock) { executed }

        override fun isCanceled(): Boolean = synchronized(lock) { canceled }

        override fun timeout(): okhttp3.Timeout = synchronized(lock) { current?.timeout() } ?: okhttp3.Timeout()

        public override fun clone(): Call = AuthorizedCall(
            client, profile, providerId, authMode, controller, original, accessToken,
        )
    }
}
