package io.github.mangi.eta.agent.mobileactions

/**
 * 端侧设备动作模型目录（FunctionGemma 270M Mobile Actions，Gemma 许可）。
 *
 * 与 [io.github.mangi.eta.agent.media.index.EmbeddingModelCatalog] 同一套约定：
 * 固定上游 revision 与 `.litertlm` 文件的字节数、SHA-256；下载源只是同一份字节的不同镜像，
 * 落盘后一律按哈希全量校验，禁止引入未校验的二进制。
 *
 * 注意两个源的可访问性不同：
 * - ModelScope 匿名可直接下载（302 跳 CDN 签名地址），因此作为默认来源；
 * - Hugging Face 该仓库是 gated（需登录并同意 Gemma 许可），匿名请求返回 401，
 *   必须由用户填写访问令牌。
 */
internal object MobileActionsModelCatalog {
    /** 下载源；不同枚举常量保证偏好可读，后续新增镜像只需扩展此处。 */
    enum class SourceId(val wire: String) {
        MODEL_SCOPE("modelscope"),
        HUGGING_FACE("huggingface");

        companion object {
            fun from(wire: String?): SourceId? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class Asset(val name: String, val bytes: Long, val sha256: String)

    /** [baseUrl] 已内嵌固定 revision 且以 `/` 结尾；[requiresToken] 为真时匿名请求会 401。 */
    data class Source(val id: SourceId, val baseUrl: String, val requiresToken: Boolean)

    /** 稳定模型 id，同时是设备目录的路径段。 */
    const val MODEL_ID = "functiongemma-270m-mobile-actions"

    /** 内容版本：用主文件 SHA-256 前缀，换模型自动落新目录，旧目录可按需清理。 */
    const val REVISION = "q8-ekv1024-33e295cb"

    const val ASSET_NAME = "mobile_actions_q8_ekv1024.litertlm"

    /** INT8 量化版：289 MB 级，官方 benchmark 峰值内存约 510 MB（CPU / 1024 上下文）。 */
    val asset = Asset(
        name = ASSET_NAME,
        bytes = 288_964_608L,
        sha256 = "33e295cbd996b419bb1de8f3f85c5b6b01ee058a2c89bdb2173cf3e6ff4ce9d0",
    )

    private const val MODEL_SCOPE_REVISION = "508f75b5913b70716a7b8f963c517b6085a911c8"
    private const val HUGGING_FACE_REVISION = "6d7eb4d4a1d25be7e049f89b8a5080561cedc41c"

    /** 默认来源排第一：ModelScope 不要求令牌。 */
    val sources: List<Source> = listOf(
        Source(
            SourceId.MODEL_SCOPE,
            "https://modelscope.cn/models/litert-community/functiongemma-270m-ft-mobile-actions/resolve/$MODEL_SCOPE_REVISION/",
            requiresToken = false,
        ),
        Source(
            SourceId.HUGGING_FACE,
            "https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions/resolve/$HUGGING_FACE_REVISION/",
            requiresToken = true,
        ),
    )

    /** 未知或空来源回退到第一个来源，偏好损坏不能影响下载可用性。 */
    fun source(id: SourceId?): Source = sources.firstOrNull { it.id == id } ?: sources.first()
}
