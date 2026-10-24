# 生图：语义分辨率与端点实测适配

直接聊天、自然语言和图片子代理共用参数解析与 `AgentImageGenerationClient`。用户侧统一使用低、中、高、超高，不把 K 档位当成服务端精确像素承诺。

## 分辨率与精确尺寸分开

| 用户名称 | 结构化 resolution | 通用 Images 长边预算 |
| --- | --- | --- |
| 低 | low | 1024 |
| 中 | medium | 1536 |
| 高 | high | 2048 |
| 超高 | ultra | 4096 |

预算只是通用客户端映射，不是每个服务商都支持四档。端点实测 profile、显式 sizes/values 映射优先。
旧输入 1K/1.5K/2K/4K 兼容归一化为上述名称；不再作为新工具 schema 或 UI 名称。旧自定义协议值及 3K 等仍可读取，但未配置映射的未知档位会失败。
通用 `size_long_edge` 路径按明确比例取预算内最大整数倍，例如 9:16+高→1152x2048，21:9+高→2044x876；这不是 Grok 的服务端档位定义。精确 `size` 原样发送，不吸附、不裁剪、不缩放，也不自动重发。

### 自然语言与子代理

```text
生成一张高分辨率、9:16的小猫照片
画三张超高分辨率、16比9的风景，并发2
生成一张中分辨率、3:4画幅的插画
生成一张插画，宽1312高736
```

```json
{"aspect_ratio":"9:16","resolution":"high","n":1}
```

子代理通过 `delegate_task.image_options` 传上述字段；任务只描述画面，不让图片模型把诊断或尺寸说明画进图片。
生图子代理设置有“默认分辨率”按钮，选项为跟随接口、低、中、高、超高。只作用于该子代理，不改共享提供商模型；自然语言/本次结构化参数可覆盖。更换绑定模型时清除旧默认档位。
参数优先级：端点/子代理默认值 < 自然语言参数 < 本次结构化字段。
引号、代码、URL、时钟、比分、参考图元数据、画面文字不是输出参数。冲突明确报错；仅给档位而缺少比例/显式尺寸映射时不猜方图。
单次批量 `n` 为1–10，本地图片分批 `concurrency` 为1–8；它是一次图片批次的局部调度，不是子代理全局并行数量上限，永不上行。部分失败保留已生成图片，失败不重试。

## 当前 Grok 中转独立 profile

自动匹配严格限定为 HTTPS 主机 `v1.123336.xyz`、默认端口、根或 `/v1` Images 基础路径，以及 API 模型 `grok-imagine-image-2.0`。不能仅凭 Grok 模型名给其他中转/官方直连套此规则。
当前配置显式填写 `eta_image_config` 的其他协议时优先遵守该配置；可以选择 `profile: generic` 退出自动预设。需要明确复用该契约时可声明 `profile: grok_verified_size`，不允许同时混入另一套字段/协议映射。

| 档位 | 已验证比例 | 实际观察输出 |
| --- | --- | --- |
| 低 | 1:1、3:4、9:16、16:9 | 1024x1024、864x1152、720x1280、1280x720 |
| 高 | 1:1、3:4、9:16、4:3、16:9 | 2048x2048、1776x2368、1584x2816、2368x1776、2816x1584 |
| 中、超高 | 尚无独立输出证据 | 本地拒绝，不降档 |

只发送实测 `size=WIDTHxHEIGHT`，不发送不起作用的 aspect_ratio/resolution。例如高+9:16发1152x2048，已观察返回1584x2816；报告分别展示发送尺寸、实测预期与真实返回尺寸。参数合法但响应偏离实测档位，也会标记不匹配，不当作达标。
21:9、1:2、低+4:3及参考图/遮罩编辑未验证，联网前拒绝。默认无参数采用已验证低档方图。显式精确像素仍按用户的精确要求校验，不将服务端不同尺寸假报为符合。
这些规则只依据当前中转实测，不保证上游永久不变；继续保留响应真实宽高校验。

## 通用端点配置

提供商/模型自定义请求体中的 JSON 对象 `eta_image_config` 仅用于客户端，永不发给文本或图片上游。Sub2API、NewAPI、Chat、Responses、Anthropic 名称本身不构成生图能力证明。Images失败不自动回退Chat。

### 原生字段映射

语义档位必须显式映射到上游值，以下仅为格式例子，不能据此认为所有模型都支持：

```json
{"eta_image_config":{"protocol":"passthrough","values":{"resolution":{"low":"1k","high":"2k"}}}}
```

这份映射会把高转换为上游 `resolution: "2k"`，中/超高缺项则拒绝。
字段映射支持最多六层，例如 `fields: {"aspect_ratio":"ratio","resolution":"size"}`；对应 values 必须按端点实际契约填写。多个参数映射到同一字段、未知值或缺项报错。

### 精确尺寸表

```json
{"eta_image_config":{"protocol":"size","sizes":{"9:16":"864x1536","9:16@high":"1152x2048"}}}
```

`size` 只用显式表；`size_long_edge` 优先用表，否则用通用长边预算。兼容读取旧 `9:16@2k` 键。
原生档位没有可验证像素表时报告 `IMAGE_RESOLUTION_UNVERIFIED`，不能仅凭请求成功或比例正确宣称分辨率达标。
真实宽高无法解码则 `IMAGE_DIMENSIONS_UNVERIFIED`；比例、精确尺寸或实测档位偏差则 `IMAGE_DIMENSIONS_MISMATCH`；数量不符则 `IMAGE_COUNT_MISMATCH`。

### JSON 图生图扩展

```json
{"eta_image_config":{"edit_protocol":"generations_image"}}
```

仅在有参考图时选择该编辑路径；文生图仍使用 `/images/generations`。
不会把多张图片拼成一张，也不会丢掉额外图片或不支持的遮罩。
当前新增的 mask 参数是底层客户端能力；没有新增涂抹遮罩界面或子代理附件传入入口。

### NovelAI 原生

```json
{
  "eta_image_config": {
    "endpoint":"novelai_native",
    "native_parameters": {
      "params_version":3, "steps":28, "scale":5,
      "sampler":"k_euler_ancestral", "noise_schedule":"native",
      "cfg_rescale":0, "negative_prompt":"", "seed":1234
    }
  }
}
```

基础地址填站点根或完整原生端点，使用已配置的 Bearer Key。
params_version、sampler 等按该模型/端点文档明确配置，不按模型名自动猜版本。
原生参数保留，实际 prompt、width/height、n_samples 由本次需求设置；v4/v5 模板的 base_caption
同步当前 prompt，保留角色字段。必须给精确尺寸或完整比例/档位，也可在 native_parameters 提供 width/height 默认值。
宽高 64–2048 且为 64 倍数，单次 1–4 张；缺少 seed 时生成随机种子。原生 quality/response_format 不支持则明确拒绝。
ZIP 限制压缩/解压总量、单项大小、条目数与图片数，不把服务端文件名解压到磁盘，检查取消状态。

默认 Anthropic 配置会在联网前说明不支持；若中转确实另有 Images 端点，可显式
`"endpoint":"openai_images"`，使用 Images URL 与 Bearer 鉴权，不把消息协议误认成生图。

## 校验与验证范围

结果检查解码的实际宽高和数量，保留原始文件。不符合目标就报告 `IMAGE_DIMENSIONS_MISMATCH`；
无法解码则 `IMAGE_DIMENSIONS_UNVERIFIED`。请求成功不代表档位或比例生效。
测试使用本地拦截器/固定二进制，不调用计费生成接口；真实中转是否完整实现该协议仍需单独验证。

## 参考

参考仓库 `lzhhhhc/imagine`，检查提交 `dc6d5bef11aba94323112f5429ffb3d83ac6e341`。
重点参考 ApiModels、ImageRepository、CreativePresets、NaiNativeClient 的协议组织。
Eta 独立实现，不移植其 UI、角色工作台、自动重试、合图与缩放裁剪逻辑。
参考项目保留放大选项且默认关闭，不能把“有 ensureResolution”说成默认会改图。
来源说明和 MIT 许可见 `third_party/imagine-reference/`。

## 媒体子代理思考设置

生图、生视频子代理的设置页（预设详情页）都显示“思考深度”，代理行也显示状态；已适配时与执行代理共用档位选择对话框，长按模型也可打开。
没有媒体端点契约时显示“当前接口未适配”并禁用，不能把聊天模型的 reasoningCapabilities 当成 Images/Videos 的能力。
这不是给媒体子代理增加一个文本规划模型，不提供思考过程展示，也不保证服务端内部实现。

目前没有按型号内置生图/视频的思考映射。对已确认开放该能力的自建/兼容端点，可在供应商或模型自定义请求体设置私有键 `eta_media_reasoning`。
以下仅是映射格式示例，**不是 Grok、Agnes、Gemini 或 Sora 的有效接口声明**；必须按实际端点文档设置 field 和 values，勿直接照抄到不支持的服务。

```json
{
  "eta_media_reasoning": {
    "image_generation": {
      "field": "reasoning_effort",
      "values": {"off": "none", "low": "low", "high": "high"},
      "default": "low"
    },
    "video_generation": {
      "transport": "videos_json",
      "field": "thinking.budget",
      "values": {"low": 1024, "high": 4096},
      "default": "low"
    }
  }
}
```

- 两个职责分开配置。可选档位仅来自明确 values；缺少 off 时不提供关闭选项。default 缺省时取枚举顺序第一档。
- 档位只影响该子代理；模型变更清空覆盖，不修改共享模型配置。旧档位不再支持时提示重选，请求前报错，不静默替换。
- 配置从 extraBodyJson 与真实 customBody 合并读取；本次选择在合并后写入，不能被模型额外参数再次覆盖。
- 私有键不发送给模型；字段支持最多六级对象路径，值仅为标量；保留输出参数和请求结构字段不能被覆盖。
- 视频需明确 transport：videos_json / videos_multipart / videos_generations / video_generations / ark_contents；此映射路径只发一次，不自动改端点尝试。multipart 仅支持顶层字段，不能假定 JSON 嵌套字段有等效表单形式。
- 图片仍遵循 eta_image_config 的既有端点选择；原生 NovelAI 的参数约束仍生效，不因此假定支持思考。
- 此处只适用于媒体子代理；直接生图/生视频不会自动使用这个独立子代理设置。
