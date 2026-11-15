# C 组讲解：安全层

这一组两个模块（09 到 10）看起来是两个不相关的主题：请求怎么被拦下来、密码和密钥怎么被加密。实际上它们回答同一个问题的两端——**谁可以进这道门，进来之后看到的数据以什么形态存在**。

## 一、先建立一个判断框架

安全相关的代码最容易写成「照着教程抄一遍」，抄完之后不知道哪一行可以删、哪一行删了就出事。所以要先把职责分开。

第一层，**请求有没有资格进来**。这一步发生在任何业务代码之前，由过滤器链完成。它只回答一个问题：这个请求带没带有效凭证。

第二层，**这个身份能不能做这件事**。同样是过滤器链的一部分，但判断依据是身份上的权限信息，不是业务数据。

第三层，**进来之后能看到什么数据**。这一层过滤器管不了，必须在业务代码里判断。比如控制台用户查任务详情，任务存在，请求合法，但他不属于这个项目——这必须在服务层拦。

第四层，**数据在磁盘上是什么形态**。密码不能可逆、密钥必须可逆但绑定归属。这一层和前面三层完全无关，是存储形态问题。

四个层次各有各的手段，混在一起就是最常见的错误：把「他能不能看这个任务」写成过滤器里的路径匹配，或者把「密码怎么存」写成服务里的一次 MD5。

## 二、串讲：一个请求从进门到落库

假设控制台用户发一个请求，要把某个任务的模型 API Key 改成新值。

请求到达时，`SecurityFilterChain` 按顺序处理。`csrf` 关掉了（前后端分离，用不上），`formLogin` 和 `httpBasic` 关掉了（这个项目不用它们），`sessionManagement` 设成 `STATELESS`（不用会话，每次请求都靠凭证自证）。

然后轮到你自己的 `OncePerRequestFilter`。它从请求头里取 token，验证，通过就构造一个 `Authentication` 放进 `SecurityContextHolder`。**这一步的关键是「放进去」，不是「验证通过」。** 不设置上下文，后面的 `anyRequest().authenticated()` 依然认为你没登录。

接着是授权判断。请求路径匹配到某个规则，可能是 `permitAll`（登录、健康检查），可能是某个角色限定，也可能是 `authenticated`。不通过就交给两个处理器之一：没认证走 `authenticationEntryPoint`（401），已认证但没权限走 `accessDeniedHandler`（403）。**这两个分工必须清楚**，否则前端无法区分「请重新登录」和「你没这个权限」。

进到控制器和服务层之后，第三层判断开始。查任务、比对 `projectId`、不属于就抛业务异常。这一层看不到 token，只看到上下文里的用户 id。

最后落库前是第四层。API Key 用 AES-256-GCM 加密，并且把「这个密钥属于哪个任务」作为 AAD 一起参与计算。于是即使有人把 A 任务的密文复制到 B 任务的字段上，解密也会失败。密码则完全不同——它不需要解密，只需要验证，所以用 Argon2 单向哈希。

四次判断，四个不同的位置，用四种不同的手段。这就是这一组的全部内容。

## 三、完整用法清单

### 过滤器链的配置项

| 配置 | 作用 | 这个项目的取值 |
| --- | --- | --- |
| `csrf(disable)` | 关掉 CSRF | 关。纯 token 认证不需要 |
| `formLogin(disable)` | 关掉表单登录 | 关 |
| `httpBasic(disable)` | 关掉 Basic 认证 | 关 |
| `logout(disable)` | 关掉默认登出端点 | 关 |
| `sessionManagement(STATELESS)` | 不创建会话 | 必须 |
| `cors(...)` | 跨域配置 | 允许控制台来源 |
| `authorizeHttpRequests` | 路径授权规则 | 见下 |
| `addFilterBefore` | 插入自定义过滤器 | `TokenAuthFilter` |
| `exceptionHandling` | 两个错误处理器 | 401 / 403 分开 |
| `dispatcherTypeMatchers(ASYNC, ERROR).permitAll()` | 放行内部转发 | **必须，见下方误区** |

### 路径规则的写法

| 写法 | 含义 |
| --- | --- |
| `requestMatchers("/api/v1/auth/**").permitAll()` | 放行 |
| `.anyRequest().authenticated()` | 其余都要登录 |
| `.hasRole("ADMIN")` | 角色（会自动加 `ROLE_` 前缀） |
| `.hasAuthority("task:write")` | 权限点，不自动加前缀 |
| `.requestMatchers(HttpMethod.GET, "/x").permitAll()` | 限定方法 |
| `.access(customAuthorizationManager)` | 自定义判断 |

### 自定义过滤器的骨架

| 步骤 | 代码 |
| --- | --- |
| 继承 | `extends OncePerRequestFilter` |
| 分流 | `shouldNotFilter`：跳过不属于自己管的路径 |
| 取凭证 | `request.getHeader("Authorization")` |
| 解析身份 | 查 Redis 会话或校验 JWT |
| 构造认证对象 | `UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities)` |
| **放进上下文** | `SecurityContextHolder.getContext().setAuthentication(auth)` |
| 放行 | `chain.doFilter(request, response)` |
| 失败时 | **立刻**写 401 响应并 `return`，不要 `chain.doFilter` |

两个细节值得单独记。第一个是构造认证对象要用 `authenticated(...)` 静态工厂，不要用三参构造器——后者在语义上是「尚未认证」，某些授权判断会因此不认。

第二个是「放进上下文」这一步不能省。验证通过后忘了 set，症状是「凭证明明是对的，还是 401」，因为后面的授权判断读的是上下文，不是你方法里的局部变量。

第三个是失败时**尽早返回**。生产的做法是写响应后直接 `return`，让请求到此为止；不要「什么都不做继续放行」，那等于把「没登录」交给后面的规则去处理，错误信息会变得不可控。

### 加密的选择

| 需求 | 算法 | 关键参数 |
| --- | --- | --- |
| 验证密码 | Argon2id | `m=19456, t=2, p=1`，盐 16 字节，哈希 32 字节 |
| 可逆加密 | AES-256-GCM | 12 字节 nonce，128 位 tag，AAD 绑定归属 |
| 密钥轮换 | 密文加版本前缀 | `v1.` 前缀，解密时按前缀选密钥 |
| 随机数 | `SecureRandom` | 绝不 `new Random()` |

### AES-GCM 的五步

| 步骤 | 注意点 |
| --- | --- |
| 生成 nonce | 12 字节，**每次加密都重新生成**，绝不能复用 |
| `GCMParameterSpec(128, nonce)` | 128 是 tag 长度（位） |
| `updateAAD(归属标识)` | 必须在 `doFinal` 之前调用 |
| `doFinal` | 返回密文 + tag 拼接 |
| 存 nonce | 和密文一起存，解密时要用 |

## 四、七个必须知道的坑

第一个，**403 变成 401**。这是本组最隐蔽的坑。`accessDeniedHandler` 里如果调 `res.sendError(403)`，容器会内部转发到 `/error`，而这次转发**会再走一遍安全链**。`STATELESS` 模式下新请求没有认证，被 `anyRequest().authenticated()` 拦成 401，于是你写好的 403 处理器等于白写。

生产的做法是**不用 `sendError`**，改成 `res.setStatus(403)` 然后自己写 body（`HttpErrorWriter`）。`setStatus` 不触发内部转发，所以生产根本踩不到这个坑。

但 `.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()` 仍然必须写，它不是用来修 403 的，而是给**异步派发**和**错误页转发**兜底——SSE、流式下载、`/error` 都会产生这两种派发类型，不放行就会在别的场景以另一种形式咬你一口。

第二个，**只验证不设置上下文**。`OncePerRequestFilter` 里验证通过后忘了 `SecurityContextHolder.getContext().setAuthentication(...)`，症状是「凭证明明对的，还是 401」。因为授权判断读的是上下文，不是你的局部变量。

第三个，**`permitAll` 和 `anonymous` 不是一回事**。前者跳过认证检查，后者要求有个匿名身份。多数时候用 `permitAll`。

第四个，**`hasRole` 会自动加前缀**。`hasRole("ADMIN")` 实际检查 `ROLE_ADMIN`，而 `hasAuthority("ADMIN")` 检查字面量 `ADMIN`。混用会导致「权限明明配了但不生效」。

第五个，**GCM 的 nonce 复用等于灾难**。同一个密钥下重复使用 nonce，攻击者能通过两次密文的异或直接恢复明文，还能伪造密文。这就是为什么 nonce 必须每次重新生成，而且要和密文一起存下来。

第六个，**没有 AAD 的加密可以被挪位置**。A 任务的密文复制到 B 任务字段上，解密照样成功，因为算法层面它是一段合法的密文。加上 AAD 之后，归属标识参与认证，挪位置就会解密失败。生产项目用 `taskUuid` 做 AAD。

第七个，**密码用哈希，密钥用加密，不能互换**。密码不该能解密（连你自己也不该能读出来），所以只能哈希；API Key 必须能还原成明文去调用第三方，所以必须可逆加密。用哈希存 API Key 会导致无法使用，用可逆加密存密码等于把密码库交给攻击者。

## 五、自测题

1. `authenticationEntryPoint` 和 `accessDeniedHandler` 分别在什么情况下被调用？各自的 HTTP 状态码是什么？
2. 为什么 `dispatcherTypeMatchers(ASYNC, ERROR).permitAll()` 是必须的？不加会出现什么现象？
3. `OncePerRequestFilter` 的「Once」是什么意思？它相比直接实现 `Filter` 解决了什么问题？
4. `STATELESS` 模式下，如果两个请求同时到达，它们的 `SecurityContext` 会互相影响吗？为什么？
5. AES-GCM 的 nonce 为什么不能复用？复用之后的攻击方式是什么？
6. AAD 参与的是加密还是认证？不加 AAD 会导致什么具体问题？
7. 密文前缀 `v1.` 的作用是什么？没有它，密钥轮换怎么做？
8. Argon2 的三个参数 `m`、`t`、`p` 分别控制什么？调高哪个参数最影响内存？
9. 为什么不能用 `MessageDigest` 直接对密码做 SHA-256？
10. 过滤器里验证失败时，为什么不能 `chain.doFilter` 之后再写响应？

## 六、学完这一组之后

你应该能独立回答：一个请求从进来到落库，要经过几次「谁能做什么」的判断，每次判断写在哪里。

也应该能判断：某个字段该用哈希还是加密，密钥轮换时旧数据怎么办。

下一组进 D 组。那里换一个维度：代码不是串行执行的，多个任务同时跑、结果边算边推。安全层回答「谁能做」，D 组回答「什么时候做完」。
