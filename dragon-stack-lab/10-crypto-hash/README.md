# 10 密码哈希与对称加密

## 演示什么

两件经常被混淆的事：用户密码为什么要用**不可逆**的 Argon2 哈希，模型 API Key 为什么要用**可逆**的 AES-GCM 加密。同时演示三个容易被忽略的设计：用 AAD 把密文绑定到归属对象上、用版本前缀为密钥轮换留位置、用脱敏和摘要处理「不能显示也不能还原」的中间态。

## 怎么运行

```bash
mvn -pl 10-crypto-hash spring-boot:run
```

真实输出。注意密文每次运行都不同，因为 nonce 是随机的。

```
哈希片段        = $argon2id$v=19$m=19456,t=2,p=1$Wq3AMKq/o5YX7UN...
正确密码匹配    = true
错误密码匹配    = false
两次哈希不相同  = true
密文            = v1.P-uwag6SnazlZcTr.IwK9Adjsuadq0PWXo3ZRDHEuE0ymfEsqBA39j3IWbbo
解密            = sk-model-key-123
脱敏展示        = sk-mode…-123
摘要查表键      = b82d0c8fb7fc066d...
换 AAD 解密     = 失败（密文不能被挪到别的任务上，符合预期）
未知版本前缀    = 被拒绝（不认识的密文版本 v2）
```

最后两行是这个模块最重要的输出。同一个密文换一个 AAD 就解不开，说明密文与归属对象绑定了；给一个 `v2.` 开头的假密文会被明确拒绝，说明格式校验是 fail-closed 的。

## 关键语法点

第一，**密码用单向哈希，密钥用可逆加密**。判断标准是「这个数据将来需不需要原样取回来」。密码你永远不需要还原，API Key 你必须在调模型时还原成明文。

第二，不要用 MD5 或 SHA-256 存密码，它们太快。快对正常业务是优点，对密码是致命缺点——显卡每秒能试几十亿次。Argon2 靠「慢」和「耗内存」提高成本。

第三，`Argon2PasswordEncoder(16, 32, 1, 19456, 2)` 的五个参数依次是：盐长度 16 字节、输出哈希 32 字节、并行度 1 个线程、内存开销 19456 KB（19 MB）、迭代 2 次。第四个数是抗显卡破解的关键，也是为什么它不是「随便调大就好」——每个登录请求都要吃掉 19 MB 内存，并发一千就是 19 GB。生产用的正是这五个值。

第四，`encode` 生成的字符串自带盐和参数，格式是 `$argon2id$v=19$m=19456,t=2,p=1$盐$哈希`，`matches` 从中读出盐重新计算。**标准做法是只存这一个字段**。

第五，但生产项目存了两列：`iam_user` 表有 `password_hash` 和 `password_salt`。`ArgonPasswordAdapter` 在 `encode` 时把盐单独切出来存一份，`matches` 时要求「库里的盐」和「哈希串里的盐」一致。这是多一层防篡改——有人只改了 hash 列没改 salt 列就会被发现。**标准做法存一个字段，生产多存一列是防御，不是必需。**

第六，同一个密码两次哈希结果不同是正常的，盐是随机的。攻击者无法用一张彩虹表批量破解所有用户。

第七，AES-GCM 是认证加密，密文被篡改解密直接抛异常；CBC 只加密不认证，改动了也能解出错误内容而不报错。新项目一律 GCM。

第八，GCM 的 nonce 是 12 字节，**每次加密都必须重新生成**。nonce 重复会彻底破坏安全性——攻击者能通过两次密文的异或还原明文，还能伪造密文。`SecureRandom` 是线程安全的，可以做成静态字段。

第九，AAD 参与完整性校验但不加密，用来绑定密文归属。生产用「用途前缀 + 归属 uuid」拼出来，比如 `svc-model-key:v1:` + taskUuid。效果是：把任务 1 的密文复制到任务 2 那一行，解密时 AAD 不匹配直接失败。这挡住了「有数据库写权限的人把别人密钥挪给自己用」。

第十，密文带 `v1` 前缀是为密钥轮换留位置。**但要说清楚：生产目前只认 `v1`**，解密时不是 `v1` 就一律抛异常，没有任何 `v2` 分支。前缀是「为将来留的位」，当前实现是「只认一个版本且严格拒绝其它版本」。这个设计比「不认识就当没有前缀硬解一遍」安全得多。

第十一，解开密文前必须校验三件事：**段数是不是 3、版本前缀是不是认识的字面量、nonce 长度是不是 12**。任何一件不满足立刻拒绝，不能「先 try 一下」。不做这层校验的话，`parts[1]` 会抛 `ArrayIndexOutOfBoundsException`——那是「程序 bug」的信号，而实际是「数据脏」，两者排查方向完全不同。

第十二，AES-256 的密钥必须**正好 32 字节**，16 字节是 AES-128，24 字节是 AES-192。这个校验要放在构造函数里，让配置错了应用直接起不来，而不是等第一次加密才在某个接口里抛 `InvalidKeyException`。

## 「完整用法」补充

**哈希这条线上只有两个动作**：注册时 `encode`，登录校验 `matches`，从来没有「解密」。要升级参数，官方做法是登录成功后用新参数重新 `encode` 一次再存回去，旧用户无感。

**还有一个容易漏的防御**：用户不存在时也要跑一次哈希，用假数据。不这么做的话，「用户不存在」会比「密码错误」快几十毫秒，攻击者靠响应时间就能枚举出哪些手机号注册过。生产在 `ArgonPasswordAdapter.consumeDummyCheck()` 里做了这件事。

**脱敏**用于日志和前端展示。生产保留头 7 位和后 4 位：前几位通常是厂商前缀（`sk-` 之类），对排查「用户填的是哪家的 key」有用，中间才是机密。

**摘要（SHA-256）** 用于「不需要还原、只需要比对」的场景：会话令牌查表、项目密钥去重。它和哈希的区别是：SHA-256 快得离谱，所以只能用于高熵数据（随机令牌），**绝不能用来存密码**——密码低熵，快哈希等于给暴力破解加速。

**数据库字段设计**要注意：密文长度比明文长，Base64 又膨胀三分之一，列宽要按「明文最大长度 × 1.4 + 前缀」估，否则会截断。

**密钥管理**：密钥写进配置文件意味着配置文件就是最高机密。正式环境必须从环境变量或密钥管理服务注入。本模块用的是随机生成的假密钥，真实密钥绝不能出现在源码里——源码会进 git，而 git 的历史删不掉。

## 完整用法清单

| 写法 | 作用 | 生产项目里的位置 |
| --- | --- | --- |
| `Argon2PasswordEncoder(16,32,1,19456,2)` | 密码哈希，五个参数固定 | `ArgonPasswordAdapter` |
| `encode(raw)` / `matches(raw, hash)` | 注册 / 登录 | 同上 |
| hash + salt 双列存储 | 防篡改（非必需） | `iam_user.password_hash` / `password_salt` |
| `consumeDummyCheck()` | 抹平时间差，防枚举 | `ArgonPasswordAdapter`、`AuthApp` |
| `AES/GCM/NoPadding` | 认证加密 | `TaskSecretCipher` |
| `GCMParameterSpec(128, nonce)` | 128 位 tag | 同上 |
| 12 字节 `SecureRandom` nonce | 每加密一次重生成 | 同上 |
| `updateAAD(用途前缀 + uuid)` | 绑定归属 | `svc-mcp:v1:`、`svc-model-key:v1:` |
| `Base64.getUrlEncoder().withoutPadding()` | 密文编码，URL 安全 | 同上 |
| `v1.nonce.密文` 三段格式 | 版本前缀 + 可拆分 | 同上 |
| 三段 / 前缀 / nonce 长度校验 | fail-closed | `TaskSecretCipher.open()` |
| 构造函数 32 字节校验 | 配置错就起不来 | `TaskSecretCipher` 构造器 |
| `mask(raw)` | 日志脱敏 | `ProjectKeyCryptoAdapter` |
| `digest(raw)`（SHA-256 + HexFormat） | 查表比对 | `ProjectKeyCryptoAdapter`、`RedisSessionStore` |
| `bcprov-jdk18on` 1.83 | Argon2 的底层实现依赖 | 生产 `pom.xml` 显式引入 |

## 自测三问

**问一**：为什么不能用 SHA-256 存密码，却可以用它算会话令牌的摘要？

答：区别在熵。密码是人选的，熵很低（几百万个常见密码就覆盖了大多数），SHA-256 太快，攻击者一秒能算几十亿个组合。会话令牌是 `SecureRandom` 生成的 32 字节，熵高到无法枚举，快哈希反而是优点——它是每次请求都要执行的热路径。

**问二**：AAD 不加密任何东西，那它到底起了什么作用？

答：它参与 GCM 的认证标签计算。改了 AAD 或换了 AAD，解密时标签校验就失败。所以密文被「挪到别处」时，只要那处的 AAD 不同，就解不开。没有 AAD 的加密，密文是一段独立自洽的数据，复制到哪里都能解开。

**问三**：生产只认 `v1`，那 `v1` 前缀不是白加了吗？

答：不白加。它让「遇到不认识的版本」这条分支现在就必须存在并被执行。等真要上 v2 时，改动只是把 `if (!VERSION.equals(...)) throw` 换成一个按版本分发的 switch，调用方一行都不用改。如果一开始不写前缀，上 v2 的那天就得写一段「猜这段密文是哪个版本」的代码，而猜错的后果是拿旧算法去解新数据。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| Argon2 参数 | `infrastructure/security/ArgonPasswordAdapter`，五个参数与本模块完全一致 |
| hash + salt 双列 | `db/migration/V1__create_identity_access.sql` 的 `password_hash`、`password_salt` |
| 时间差防御 | `ArgonPasswordAdapter.consumeDummyCheck()`、`application/AuthApp` |
| AES-256-GCM 封装 | `infrastructure/security/TaskSecretCipher`，本模块的 `AesGcmCipher` 是它的简化版 |
| AAD 绑定归属 | `svc-mcp:v1:` 和 `svc-model-key:v1:` 两个用途前缀 + uuid |
| 密文格式 | `v1.nonce.密文`，Base64 URL 无填充 |
| 格式校验 | `TaskSecretCipher.open()` 的三重校验 |
| 脱敏 | `infrastructure/security/ProjectKeyCryptoAdapter` |
| 摘要 | 同上，以及 `RedisSessionStore` 的令牌 key |
| 密钥来源 | `project-api.key-encryption-secret`，目前写在 `application.yaml` 里 |
| BouncyCastle | 生产显式引 `bcprov-jdk18on` 1.83，Argon2 实现依赖它 |

## 行数说明

两个类合计约一百五十行（含注释），超出较多。`AesGcmCipher` 的一百一十行里，注释占了六十多行——其中「为什么密文要带 v1 但生产只认 v1」和「为什么解开前要校验三件事」这两段不是语法知识，是不写就会写错的判断，删掉代码看着更短，但学不到东西。

`Lab10Application` 里的两个 try-catch 各八行，分别是 AAD 换绑和未知版本前缀的结论，必须保留。
