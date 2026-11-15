# 09 Spring Security 过滤器链

## 演示什么

一个纯 JSON 接口的服务，怎么把 Spring Security 从「默认给所有接口套表单登录」改造成「令牌认证 + 分角色授权」。这份配置与生产 `harness_saas/config/SecurityConfiguration.java` 逐项对齐，包括两个自定义过滤器、统一的错误信封和容器级注册开关。

## 怎么运行

```bash
mvn -pl 09-security-chain spring-boot:run
```

服务监听 18090 端口。**注意避开 8080**，因为你的本机 8080 上跑着 `harness_saas`，请求会打到那个服务上，你会收到 `SESSION_INVALID` 这样的错误码，然后误以为自己的配置有问题。

如果启动日志里出现 `Using generated security password`，说明 `Lab09Application` 的 `exclude` 没生效，容器里被塞了一个内存用户，这跟本 demo 的认证逻辑毫无关系。

## curl 清单

一共十条，覆盖了下面这些分支。

```bash
B=http://localhost:18090
curl -s -X POST $B/api/v1/auth/login                                          # 1 放行，POST 限定
curl -s $B/api/v1/auth/login                                                  # 2 同一路径 GET 被拦
curl -s $B/api/v1/me                                                          # 3 无凭证 401
curl -s -H 'Authorization: Bearer console-token' $B/api/v1/me                 # 4 令牌有效 200
curl -s -H 'Authorization: Bearer nope' $B/api/v1/me                          # 5 令牌无效 401 由过滤器写
curl -s -H 'Authorization: Bearer console-token' $B/api/v1/audit              # 6 已认证但无权限 403
curl -s -H 'Authorization: Bearer project-key' $B/api/service/v1              # 7 精确路径 200
curl -s -H 'Authorization: Bearer project-key' $B/api/service/v1/ping         # 8 子路径 200
curl -s -H 'Authorization: Bearer console-token' $B/api/service/v1/ping       # 9 两个过滤器并存
curl -s -i -X OPTIONS -H 'Origin: http://localhost:3000' \
     -H 'Access-Control-Request-Method: GET' $B/api/v1/me                     # 10 CORS 预检
```

本机实测结果。

```
1  POST 登录接口      = 200  {"token":"console-token"}
2  GET  登录接口      = 401  {"code":"SESSION_INVALID","message":"请先登录","data":null}
3  无凭证 /me         = 401  {"code":"SESSION_INVALID","message":"请先登录","data":null}
4  控制台 /me         = 200  {"id":1,"name":"console-user"}
5  无效令牌 /me       = 401  {"code":"SESSION_INVALID","message":"会话已失效","data":null}
6  控制台 /audit      = 403  {"code":"FORBIDDEN","message":"无权执行此操作","data":null}
7  密钥 /service/v1   = 200  服务端接口可访问
8  密钥 /service/v1/ping = 200 服务端接口可访问
9  控制台 /service    = 401  {"code":"API_KEY_INVALID","message":"项目服务密钥无效或已吊销","data":null}
10 CORS 预检          = 200  带 Access-Control-Allow-Origin
```

第 3 条与第 5 条都是 401，但来源不同：前者连 `Authorization` 头都没有，过滤器不做判断，交给 `authenticationEntryPoint` 统一返回；后者带了令牌但校验失败，过滤器直接写响应并 return，不再往下走。把两条的 message 对照着看，就能分辨响应是哪一层产生的。

这份清单没有覆盖的分支有三个，都属于「要另外搭场景」的部分：`ASYNC` 派发需要真的写一个 `SseEmitter` 接口才会发生；`ERROR` 派发在不使用 `sendError` 之后基本不会出现；`FilterRegistrationBean` 的效果只能从启动日志里那行 `Filter tokenFilterNotInContainer was not registered (disabled)` 看出来，curl 看不到。

## 关键语法点

第一，引入 `starter-security` 之后默认会开启 CSRF、表单登录、HTTP Basic、登出、会话五样东西。做纯 JSON 接口时前四样都要关，会话要设成 `STATELESS`。

第二，CSRF 可以关的前提是不用 Cookie 认证。如果接口依赖 Cookie 会话，关掉就等于开了后门。

第三，`STATELESS` 还要配 `.requestCache(c -> c.disable())`。不关的话，401 的请求仍会被 `HttpSessionRequestCache` 记进 HttpSession 等着「登录后跳回原地址」，既白白占用内存，也与无状态语义冲突。生产 `:37` 写了这一行。

第四，`authorizeHttpRequests` 从上往下匹配，第一条命中就生效，所以具体路径必须写在 `anyRequest()` 之前。

第五，`requestMatchers(HttpMethod.POST, ...)` 是带方法的重载。放行只对 POST 生效，同一个路径用 GET 打过来仍然 401，第 1、2 条对比的就是这个差异。

第六，服务端接口要写两条并列规则：`/api/service/v1` 与 `/api/service/v1/**`。只写带斜杠的那条会漏掉精确路径本身，表现为该路径返回 401 而不是 403。

第七，401 由 `authenticationEntryPoint` 返回，403 由 `accessDeniedHandler` 返回。两个必须分开，否则前端分不清「该重新登录」和「就是没权限」。

第八，自定义过滤器继承 `OncePerRequestFilter` 而不是实现 `Filter`，前者保证一次请求只经过一次。

第九，认证放在过滤器里，授权放在配置里。但错误响应并不是只在一处：入口点、拒绝处理器、两个过滤器的失败分支，四条路径都会写响应，所以必须有 `JsonErrors` 这样的统一出口。

第十，`addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)` 保证自定义过滤器在框架做认证判断之前执行；第二个过滤器再以第一个为锚点插入，顺序就固定了。

## 两个真实的踩坑记录

### 坑一：demo 里 403 变成了 401

第一版给拒绝处理器写的是 `res.sendError(403, "无权访问")`。结果控制台用户访问需要更高权限的接口时，`accessDeniedHandler` 确实执行了，异常类型也确实是 `AuthorizationDeniedException`，但前端收到的响应码是 401，body 里是 Spring Boot 默认的 `{timestamp,status,error,path}`，`message` 一个字都没有。

原因是 `sendError` 会把请求交给容器去做错误页渲染，这次内部转发在安全链看来是一个新请求，而 `STATELESS` 模式下它不带任何认证信息，于是又被 `anyRequest().authenticated()` 拦一次，最终返回 401。加一行放行内部派发类型可以让这个 demo 正常工作。

生产不存在这个问题，因为它根本不用 `sendError`：`HttpErrorWriter` 直接 `setStatus` 再写 body，全程没有 ERROR 派发。但生产的 `dispatcherTypeMatchers(ASYNC, ERROR)` 仍然必须留着，作用不是修 403，而是兜底：一是 `SseEmitter` 这类异步响应在派发阶段会再次经过过滤链，二是容器自身触发的错误页转发同样会经过，不放行就会在这些场景被误判成未认证。

结论可以记成一句：demo 用 `sendError` 会踩这个坑，生产用 `setStatus` 所以踩不到，但那一行 `dispatcherTypeMatchers` 两边都要留。

### 坑二：端口被打到别的服务上

第一次运行时十条里有好几条返回 401，返回体是 `{"code":"SESSION_INVALID"}`。那个错误码不属于这个 demo，它来自本机 8080 上正在运行的 `harness_saas`。加上显式端口配置后正常。

教训是给每个 demo 分配独立端口，并且在 README 里写清楚端口号，不要依赖默认值。

### 坑三：过滤器被注册了两次

自定义过滤器加了 `@Component`，又用 `addFilterBefore` 插进安全链，看起来只写了一次，实际上 Boot 会把它当成普通 `Filter` Bean 自动注册到 servlet 容器，于是同一个实例被注册了两遍。之所以没出问题，只是因为 `OncePerRequestFilter` 自带「本次请求已执行」标记，第二次进来时直接跳过。一旦有人换了基类、或者加个 `@Order` 打乱顺序，就会变成真的双跑，表现为认证执行两遍、日志重复、下游计数器翻倍。

处理办法是显式关掉容器注册，生产对两个过滤器都这么做了（`:85-99`）。

```java
@Bean
FilterRegistrationBean<TokenAuthFilter> tokenFilterNotInContainer(TokenAuthFilter filter) {
    var registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
}
```

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| 整条过滤器链的骨架 | `config/SecurityConfiguration.java:25-67` |
| 控制台令牌过滤器 | `infrastructure/security/BearerAuthenticationFilter.java:29-35`（跳过逻辑）、`:56-65`（认证与失败） |
| 服务端密钥过滤器 | `infrastructure/security/ProjectKeyAuthenticationFilter.java:33-36` |
| 两个过滤器的插入顺序 | `SecurityConfiguration.java:64-65`，项目密钥排在 Bearer 之前 |
| 统一错误输出 | `infrastructure/security/HttpErrorWriter.java:21-29`，信封是 `{code, message, data}` |
| 错误码与 HTTP 状态的映射 | `domain/iam/ErrorCode.java`，`SESSION_INVALID`/`API_KEY_INVALID` 都是 401 |
| 容器级请求体大小限制 | `infrastructure/security/JsonRequestSizeFilter.java:29-48` |
| `dispatcherTypeMatchers` | `SecurityConfiguration.java:41-42` |
| CORS | `SecurityConfiguration.java:74-83`，配置项在 `application.yaml:42` |
| `requestCache` 关闭 | `SecurityConfiguration.java:37` |
| 关闭容器注册 | `SecurityConfiguration.java:85-99` |

### 完整用法清单

| 用法 | 本 demo | 说明 |
| --- | --- | --- |
| `csrf().disable()` | 有 | 无 Cookie 才可关 |
| `cors(withDefaults())` | 有 | 必须与 `CorsConfigurationSource` Bean 配套 |
| `formLogin().disable()` | 有 | 纯接口不需要登录页 |
| `httpBasic().disable()` | 有 | 避免浏览器弹原生登录框 |
| `logout().disable()` | 有 | 打开会多出一个 `/logout` 端点 |
| `requestCache().disable()` | 有 | 与 STATELESS 配套 |
| `sessionManagement(STATELESS)` | 有 | 不创建 HttpSession |
| `authorizeHttpRequests` | 有 | 四条规则，顺序即优先级 |
| `dispatcherTypeMatchers` | 有 | 放行 ASYNC 与 ERROR |
| `requestMatchers(HttpMethod.POST, ...)` | 有 | 方法限定 |
| `requestMatchers(精确路径)` | 有 | 必须与 `/**` 并列写 |
| `hasAuthority` | 有 | demo 用它制造 403 |
| `exceptionHandling` | 有 | 入口点与拒绝处理器分开 |
| `addFilterBefore` | 有，两次 | 第二个以第一个为锚点 |
| `shouldNotFilter` | 有，两个过滤器 | 各自只处理一段路径 |
| `FilterRegistrationBean.setEnabled(false)` | 有 | 防重复注册 |
| `@EnableConfigurationProperties` | 有 | CORS 来源外置 |
| `@AuthenticationPrincipal` | 有 | 直接注入 record 主体 |
| `UserDetailsServiceAutoConfiguration` 排除 | 有 | 去掉内存用户与随机密码日志 |

生产还有一处 demo 没做的：`infrastructure/security/JsonRequestSizeFilter`。它是容器级过滤器，不在安全链里，作用是给任务接口的 JSON 请求体设上限，超过就返回 413。它加 `@Component` 但没有被 `addFilterBefore`，正好是「容器级」与「链内」两种注册方式的对照。

## 生产里没有的东西

第一，没有 `AuthenticationManager`、`UserDetailsService`、`PasswordEncoder` 这三个 Bean，全仓零处。看到这里如果你还想找 Spring 标准登录流程，是找不到的。

第二，登录就是一个普通 Controller 方法（`controller/AuthController.java:31-36`），校验账号密码之后调用 `RedisSessionStore.issue/rotate/revoke` 签发、续期、吊销令牌，返回体是 `WebResponse`。

第三，所以认证的全部入口只有两个过滤器。角色判断也不在 `hasAuthority` 里，而是在应用层 `application/AuthorizationApp.java:17-52`，过滤器只授权限标记，不做规则消费。

## 行数说明

生产 `SecurityConfiguration` 共 107 行（`wc -l`，含注释与空行），四个安全类（`SecurityConfiguration`、`BearerAuthenticationFilter`、`ProjectKeyAuthenticationFilter`、`HttpErrorWriter`）合计 269 行，是安全类模块里最长的。

本模块七个类有效代码 120 行（不含注释、空行与 import）。`SecurityConfiguration` 一个类占了 38 行，其中接近一半是注释——每关掉一个默认能力都要说明理由，否则你抄走之后不知道哪一行可以删；真正无法压缩的是链式配置、两个错误处理器和容器注册开关。

## 自测三问

第一，把 `requestCache` 那行删掉，再发一个 401 请求，然后看服务端是否多出了一个 HttpSession。如果看不出来，就在入口点里打印 `request.getSession(false)`，思考它为什么不为空。

第二，把 `/api/service/v1` 这条精确路径从配置里删掉，用项目密钥请求 `/api/service/v1`，你会得到 401 还是 403，为什么。再用控制台令牌请求同一条路径，结果又是什么，两个过滤器分别起了什么作用。

第三，把 `TokenAuthFilter` 改成直接实现 `Filter`（不再继承 `OncePerRequestFilter`），保留 `@Component`，同时删掉 `FilterRegistrationBean` 里那行 `setEnabled(false)`，观察认证逻辑执行了几次。你会看到日志里的认证行出现两遍，这就是容器注册与链内注册叠加的结果，而 `OncePerRequestFilter` 恰好把它掩盖了。
