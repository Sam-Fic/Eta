# 豆包 ASR 与个人音色

## 设置入口

设置 → 语音 → 语音对话：豆包 ASR API Key、识别资源、离线/云端切换；声音复刻管理在「我的声音」。
ASR 与复刻各保存自己的 API Key；不会继承朗读或实时会话配置。
音频仅在用户主动录音/选择样本时上传；ASR 失败不回退其他引擎。

## ASR

使用官方 `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async` 二进制协议，16 kHz / mono / PCM16，200ms 分包；默认 2.0 小时资源，可选择 1.0 或并发资源。
开启二遍识别和全量假设，文本替换而非追加；最终包、错误、gzip、消息边界有校验。10 秒无文字或 60 秒单次录音限制。取消时关闭麦克风和 socket。
听写入口已并入语音对话：输入区只保留「语音对话」与「豆包实时通话」两个入口；端到端实时会话仍使用其内置 ASR。
每轮识别文本提交给模型后立即从聆听条清空；长文本保持单行，并平滑跟随到用户最新说出的尾部。
旧版只开听写的配置会一次性迁移到语音对话，不会重复覆盖用户之后修改的识别设置。

## 复刻及个人音色

使用 V3 `voice_clone` 与 `get_voice`。导入 WAV/MP3/OGG/M4A/AAC 样本，最大 10 MiB；创建自动生成 ID 的后付费音色。训练前保存本地身份，完成后保存状态、模型能力及试听链接。进程退出后可查询原任务，不会自动重训。
试听文本和正式合成均可能计费，首次正式使用可能触发音色费用；界面需用户确认才进入可选列表。临时音色 7 天后可能被服务端删除，试听链接约 1 小时有效；重新查询更新链接。

已购买的音色可通过 `BatchListMegaTTSTrainStatus` 分页拉取（Success/Active），AK/SK HMAC-SHA256 签名，仅在设置页临时使用。导入时逐个用 API Key 查询模型能力；不把列表返回等同于合成可用。
音色按 API Key 指纹隔离（同账户不同 Key 不自动合并）。朗读仅放入状态 2/4 且 model_type 4/5 的 ICL 2.0 音色，使用 seed-icl-2.0；不用于 seed-audio create 接口。V3 音色也在实时会话中提供选择，实际权限由服务端判断。无效个人音色明确失败，不静默回退默认音色。

当前样本入口为系统文件选择器；可从录音 App 选择录音。不自动购买预付费槽位，不自动迁移/重训已有音色。

## 官方依据

- https://docs.volcengine.com/docs/DoubaoVoice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh
- https://docs.volcengine.com/docs/DoubaoVoice/LargemodelstreamingautomaticspeechrecognitionAPI?lang=zh
- https://docs.volcengine.com/docs/DoubaoVoice/SoundReplicaAPI-V3?lang=zh
- https://docs.volcengine.com/docs/DoubaoVoice/tone-query-http?lang=zh
- https://docs.volcengine.com/docs/DoubaoVoice/BatchListMegaTTSTrainStatus-PageQuerySpeakerIDStatus?lang=zh
- https://docs.volcengine.com/docs/DoubaoVoice/HTTPChunkedSSEUnidirectionalStreaming-V3?lang=zh

真实云端鉴权、计费权限、音质和录音端到端行为需要配置账户后实机验证；协议单测与编译不能替代这些验证。


## 免费/预付费槽位与后付费创建

默认选择已有/免费槽位，传控制台真实 `S_…` 作为 `speaker_id`，不发送 `custom_speaker_id`。
可以直接填写 ID，或通过 AK/SK 同步 Unknown、Training、Success、Active 四种状态的槽位。
目录数据不代表可合成；仍由 get_voice 校验训练状态和模型能力。单个查询失败会保留带错误提示的目录条目。
训练前明确提示可能覆盖原声音、消耗训练次数；重新训练清除旧试听、能力与正式使用确认，防止使用旧状态。
后付费是显式选项，只在该选项下自动生成 custom_speaker_id，不因槽位 ID 缺失或请求失败自动切换。
45000030 提示检查同项目声音复刻权限与单独的后付费音色服务；上传明确 4xx 拒绝显示“请求被拒绝”，网络/5xx 未确认不自动重训。
训练成功后的查询失败保留最近已知状态，不把查询拒绝误标成训练拒绝。

官方依据：
- https://docs.volcengine.com/docs/DoubaoVoice/Soundreplicationorderingandusageguide?lang=zh （免费音色归入预付费；后付费需单独开通）
- https://docs.volcengine.com/docs/DoubaoVoice/tone-training-http?lang=zh （V3 X-Api-Key；speaker_id 与 custom_speaker_id）
- https://docs.volcengine.com/docs/DoubaoVoice/BatchListMegaTTSTrainStatus-PageQuerySpeakerIDStatus?lang=zh （Unknown 等状态）

尚未使用真实音色执行训练，免费额度是否适用和资源授权仍需真实账户验证。


## 简化设置入口

语音设置保留朗读、语音对话、实时通话、我的声音四个分区；语音对话分区提供对话总开关、识别方式、语音包与回复朗读音色，原独立听写页已移除。
识别方式仅展示所选引擎所需内容，资源代号改为中文服务名称并折叠到高级设置。
我的声音将只读导入与录音训练区分；录音训练分为选择名额、选择录音、确认上传三步。
选择录音不会立即训练，最后一步展示覆盖/次数/计费提示并要求样本授权确认。
API Key 与 AK/SK 不写入导航状态；更换页面后高级参数默认折叠，旧配置继续有效。
连接设置的保存不等于云端鉴权验证成功，界面明确提示通过实际识别验证。


## 名额选择引导

制作第一步优先显示本机已同步的音色卡片，按尚未制作的名额优先排序，展示剩余训练次数。
在制作流程内直接提供“查找我的音色名额”，无需退回高级设置。AK/SK 仅用于列表读取，默认项目为 default，可展开更改。
手填 ID 移入备用入口，并说明控制台“已复刻”过滤会隐藏尚未使用的名额。空列表不代表免费额度耗尽。
Training/Active/Expired/Reclaimed 或剩余次数为零的名额不能选择训练；未确认状态不冒称可合成。
同步成功或收起表单清空 AK/SK；不得把 API Key、AK/SK 自动当作另一种凭据。


## 控制面签名与 Key ID 记忆

列表请求使用独立 OkHttpClient，禁止重定向，避免模型网络拦截器更改签名请求。
对齐官方 Python SDK SignerV4：签入 content-type、host、x-content-sha256、x-date，UTF-8 摘要与实际发送字节一致。
参考：https://github.com/volcengine/volc-sdk-python/blob/main/volcengine/auth/SignerV4.py
固定向量和本地 HTTP 线上的头/中文请求体测试不等于真实账户鉴权成功；仍需验证真实 AK/SK。
根据用户要求，AK Key ID 与项目名在本机自动保存，提供清除按钮；SK 仍仅临时使用，成功后或离开表单清空。
此前文档中的“AK/SK 均不保存”由本节替代。应用禁用 Android 自动备份；Key ID 不写入助手持久记忆。


## 本机保存与入口调整（覆盖前述临时 SK 约定）

用户要求同时记住 SK：使用 Android Keystore AES-GCM 加密，密文仅存独立私有偏好；以 AK 为 AAD，避免跨 Key ID 复用旧 SK。修改 AK 清空旧 SK；提供统一清除，保存失败显示明确提示。SK 不加入日志或助手记忆。
“我的声音”入口迁入朗读页面；删除“帮助与诊断”页面，DoubaoDiag 沿现有 AppFileLogger 路径统一导出/清空。
实际运行官方 Python SDK（commit d88be4d8028e35563b27ff1bafe3c1f7ed1ab656）对同一固定向量签名，Authorization 与现有 Kotlin 测试一致，签名 9ffc2ff0b59fd1dca028f08217d73c45e140c85f369536157184d03aedc9d600。真实账户 SignatureDoesNotMatch 仍未解决，不能据测试声称密钥错误或鉴权成功。
