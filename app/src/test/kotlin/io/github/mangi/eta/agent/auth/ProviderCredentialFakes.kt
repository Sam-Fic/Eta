package io.github.mangi.eta.agent.auth

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 凭据链路的测试替身。
 *
 * 真实存储把凭据加密在 AndroidKeyStore 里，Robolectric 没有这个 Keystore，
 * 所以凡是要验证「凭据被写入 / 被清掉」的用例都注入这里的实现，
 * 而不是让测试绕过存储直接断言 provider 表。
 */
internal class MemoryProviderCredentialStorage : ProviderCredentialStorage {
    private val credentials = ConcurrentHashMap<String, ProviderCredential>()

    override fun read(providerId: String): ProviderCredential? = credentials[providerId]

    override fun write(providerId: String, credential: ProviderCredential) {
        credentials[providerId] = credential
    }

    override fun delete(providerId: String) {
        credentials.remove(providerId)
    }

    /** 直接暴露一份快照，供断言「还剩哪些凭据」。 */
    fun snapshot(): Map<String, ProviderCredential> = credentials.toMap()
}

/** 不接触网络的登录客户端；未显式给出的动作一律视为意外调用。 */
internal class FakeProviderOAuthClient(
    private val login: () -> ProviderCredential = { error("Unexpected login") },
    private val refresh: (ProviderCredential, AgentRunController) -> ProviderCredential =
        { _, _ -> error("Unexpected refresh") },
) : ProviderOAuthClient {
    val refreshes = AtomicInteger()

    override fun login(
        provider: OAuthProvider,
        controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit,
    ): ProviderCredential = login()

    override fun refresh(credential: ProviderCredential, controller: AgentRunController): ProviderCredential {
        refreshes.incrementAndGet()
        return refresh.invoke(credential, controller)
    }

    override fun revoke(credential: ProviderCredential, controller: AgentRunController): Boolean = true
}
