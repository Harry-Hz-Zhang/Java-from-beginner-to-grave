# 14 Redis 三种用法

## 演示什么

同一个 `StringRedisTemplate` 支撑的四件事：带毫秒过期的会话存储、固定窗口限流、带负缓存的读穿缓存，
以及 Redis 不可用时的降级。前三者分别对应生产里三个独立的类，写法与它们一致，不是「简化版建议」。

## 怎么运行

这个模块需要本地 Redis。本机的 `bidding-agent-redis` 带密码，密码通过环境变量传入。

```bash
REDIS_PASSWORD=你的密码 mvn -pl 14-redis-cache spring-boot:run
```

真实输出。

```
会话内容 = harry
五次请求放行情况 = true true true false false
不存在键回源次数 = 1，首次加载 = 303 毫秒，第二次 = 1 毫秒
```

限流前三次放行、后两次拦截；不存在的键只回源一次，说明五秒的负缓存生效了；缓存第二次一毫秒，
说明数据从 Redis 直接返回，没有再执行加载逻辑。

## 关键语法点

第一，用 `StringRedisTemplate` 而不是 `RedisTemplate`。后者默认 JDK 序列化，值在 `redis-cli` 里是乱码，排查时完全没法读。

第二，三个操作入口：`opsForValue` 管字符串，`opsForHash` 管哈希，`opsForList` 管列表，命名与 Redis 命令一一对应。`INCR`、`EXPIRE` 这类键级命令直接用 template 上的方法，或者像限流这样写进脚本。

第三，限流是 Lua 脚本，不是「建议用脚本」。`INCR` 与 `PEXPIRE` 拆成两条命令时，进程在中间崩掉会让计数器永不过期、客户端被永久限流；生产 `infrastructure/session/RedisRateLimiter.java:18-25` 就是把这两步合成一个 `DefaultRedisScript`，全仓一共九个 Lua 脚本。脚本执行期间不插入别的命令，两步天然原子。

第四，它是固定窗口，不是滑动窗口。第一个 `INCR` 之后才 `PEXPIRE` 设整个窗口的过期，窗口内计数一路累加，到点整体清零，没有滑动平均。pom 里原先写「滑动窗口」是错的。

第五，key 带 Redis Cluster 的 hash tag：`lab:{iam}:session:xxx`。集群按 key 哈希分片，但只取第一个 `{` 到 `}` 之间的内容做哈希，所以脚本里动态拼出来的键一定落在同一个槽位；没有 hash tag 就会跨节点取键，直接报 `CROSSSLOT`。生产 `infrastructure/session/RedisSessionStore.java:19` 的 `iam:{iam}:`、`infrastructure/security/RedisProjectKeyCache.java:20-22` 的 `project:{project-auth}:` 都是这个原因，后者的注释原文是 One hash tag keeps Lua's dynamically derived organization keys in a single Redis Cluster slot。

第六，TTL 用毫秒级。生产对过期精度的要求到十秒、三十秒级（`RedisSessionStore.java:82`、`RedisRateLimiter.java:33`、`RedisProjectKeyCache.java:52,115`），而 `SET ... EX` 只有秒级，不足一秒会被截断。本模块用 `Expiration.milliseconds(...)`，它生成的是 `SET key value PX n`，与 `PSETEX` 同精度。

第七，Redis 不可用要降级成 503，不能让异常冒成 500。生产 `RedisSessionStore.java:212-214` 返回「会话服务暂不可用」，`controller/GlobalExceptionHandler.java:51-54` 把 `DataAccessException` 统一转成 503 `SERVICE_UNAVAILABLE`。本模块在 `guard` 里捕获 `RedisConnectionFailureException` 抛 `Unavailable`，调用方按 503 打印。

第八，负缓存解决缓存穿透：查一个根本不存在的键，每次都回源打数据库。把「查不到」也缓存下来，TTL 给得很短即可。生产 `RedisProjectKeyCache.java:34-36,48-49` 存一个 `invalid` 标记，TTL 五秒。

第九，并发回源生产**没有**用分布式锁。全仓没有 `setIfAbsent` 或 `SETNX`，用的是 generation 版本号加 fence 令牌：写缓存时校验版本号，版本变了就丢弃这次写入（`RedisProjectKeyCache.java:56-75,102-117`）。这一点与原 README 写的「用分布式锁解决」不符，以代码为准。

第十，token 先做 SHA-256 摘要再进 key。会话 token 是凭证，明文拼进 key 会原样出现在 `redis-cli`、慢查询日志与监控采样里。生产 `RedisSessionStore.java:91-92,201-210` 就是先摘要再拼 key。

## 完整用法清单

| 写法 | 说明 | 生产位置 | 生产是否使用 |
| --- | --- | --- | --- |
| `StringRedisTemplate` | 字符串序列化，可读可查 | `RedisSessionStore`、`RedisRateLimiter`、`RedisProjectKeyCache` | 使用 |
| `DefaultRedisScript` | 多条命令原子执行 | `RedisRateLimiter.java:18-25` 等 9 处 | 使用，9 个脚本 |
| `INCR` + `PEXPIRE` | 固定窗口限流 | `RedisRateLimiter.java:18-25` | 使用 |
| `SET ... EX` 秒级过期 | 简单场景够用 | 无（生产要毫秒） | 未使用 |
| `Expiration.milliseconds` / `PSETEX` | 毫秒级 TTL | `RedisSessionStore.java:82`、`RedisRateLimiter.java:33` | 使用 |
| hash tag `{iam}` / `{project-auth}` | 集群同槽位 | `RedisSessionStore.java:19`、`RedisProjectKeyCache.java:20-22` | 使用 |
| 负缓存（空值 + 短 TTL） | 防缓存穿透 | `RedisProjectKeyCache.java:34-36,48-49` | 使用，5 秒 |
| SHA-256 摘要 token | 不把凭证写进 key | `RedisSessionStore.java:91-92,201-210` | 使用 |
| `RedisConnectionFailureException` → 503 | 依赖不可用降级 | `RedisSessionStore.java:212-214`、`GlobalExceptionHandler.java:51-54` | 使用 |
| 分布式锁 `setIfAbsent` / `SETNX` | 防并发回源 | 无 | 未使用，改用 generation + fence |
| `@Cacheable` 注解缓存 | 声明式缓存 | 无 | 未使用，全部手写 Cache-Aside |

## 自测三问

1. 把 `allow` 的窗口改成 `Duration.ofMillis(1500)`，连打五次，第几次会被拦，客户端过多久能重新放行。
2. 把负缓存那段 `5_000` 改成 `0`，`不存在键回源次数`变成几，为什么会变。
3. 把 Redis 停掉再跑，输出的是 500 还是 503，`guard` 捕获的是哪个异常类型。

## 行数说明

两个类合计九十行（不含注释与空行），在纪律之内。
