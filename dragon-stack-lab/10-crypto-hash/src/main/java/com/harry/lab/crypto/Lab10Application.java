package com.harry.lab.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * 演示一：密码为什么必须用单向哈希，以及 Argon2 的五个参数各是什么意思。
 *
 * <p>【密码存哈希，密钥存加密，这两件事不能混】密码你永远不需要还原，所以用单向哈希，
 * 数据库泄露也拿不到原密码。而模型 API Key 你必须在调用模型时还原成明文，所以只能用
 * 可逆加密。判断标准很简单：这个数据将来需不需要原样取回来。
 *
 * <p>【为什么不用 MD5 或 SHA-256】因为它们太快了。快对正常业务是优点，对密码是致命缺点：
 * 攻击者用显卡每秒能试几十亿次。Argon2 的设计目标就是「慢且耗内存」，
 * 让暴力破解的成本高到不可接受。
 *
 * <p>【五个构造参数的准确含义】
 * 第一个 16 是盐长度（字节），盐保证同样的密码在不同用户那里得到不同哈希；
 * 第二个 32 是输出哈希长度（字节）；
 * 第三个 1 是并行度，即用几个线程计算；
 * 第四个 19456 是内存开销（KB，也就是 19MB），这是 Argon2 抗显卡破解的关键；
 * 第五个 2 是迭代次数。
 * 生产项目用的正是这五个值，把它当成这个项目的标准配置记下来即可。
 *
 * <p>【matches 为什么不需要你保存盐】encode 生成的字符串里已经包含了盐和参数，
 * 格式形如 $argon2id$v=19$m=19456,t=2,p=1$盐$哈希。
 * matches 会把原文和这个字符串里的盐重新算一次再比对，所以标准用法是只存这一个字段。
 *
 * <p>【但生产项目存了两列，为什么】harness_saas 的 iam_user 表有 password_hash 和
 * password_salt 两列，ArgonPasswordAdapter 在 encode 时把盐单独切出来再存一份，
 * matches 时要求「库里存的盐」和「哈希串里的盐」一致，否则直接判失败。
 * 这是多一层防篡改：有人只改了 password_hash 列而没改 salt 列，就会被发现。
 * 记住标准做法是存一个字段，生产多存一列是为了防御，不是必需。
 *
 * <p>【同一个密码两次哈希结果不同是正常的】因为盐是随机的。这正是它比 MD5 安全的地方：
 * 攻击者无法用一张彩虹表批量破解所有用户。
 */
@SpringBootApplication
public class Lab10Application implements CommandLineRunner {

    public static void main(String[] args) {
        SpringApplication.run(Lab10Application.class, args);
    }

    // 演示用的假密钥，32 字节随机值的 Base64。真实密钥只能来自环境变量或密钥管理服务，
    // 绝不能像这样写在源码里——源码会进 git，git 的历史删不掉。
    private static final String DEMO_KEY = "q5Fc2xXyQ0qtr5qXc3x6f6KGJcFVNbCX43oIO7VLrMw=";

    // AAD 的两个用途前缀提到常量里。生产项目里这两个值也是常量，
    // 手写字面量迟早会拼错一个字符，而拼错的表现是「解密失败」，很难查。
    private static final String AAD_MODEL_KEY = "svc-model-key:v1:";

    @Override
    public void run(String... args) throws GeneralSecurityException {
        var encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        // 密码长度要用生产规则校验过的：IdentityRules 要求 10 到 128 位。
        // 用 8 位的 "p@ssw0rd" 演示，搬进生产会被直接拒掉。
        var raw = "harness-demo-2024";
        var hash = encoder.encode(raw);
        System.out.println("哈希片段        = " + hash.substring(0, 46) + "...");
        System.out.println("正确密码匹配    = " + encoder.matches(raw, hash));
        System.out.println("错误密码匹配    = " + encoder.matches("wrong-password", hash));
        System.out.println("两次哈希不相同  = " + !hash.equals(encoder.encode(raw)));

        var cipher = new AesGcmCipher(DEMO_KEY);
        var sealed = cipher.encrypt("sk-model-key-123", AAD_MODEL_KEY + "task-1");
        System.out.println("密文            = " + sealed);
        System.out.println("解密            = " + cipher.decrypt(sealed, AAD_MODEL_KEY + "task-1"));
        System.out.println("脱敏展示        = " + mask("sk-model-key-123"));
        System.out.println("摘要查表键      = " + digest("sk-model-key-123").substring(0, 16) + "...");

        try {
            cipher.decrypt(sealed, AAD_MODEL_KEY + "task-2");
            System.out.println("换 AAD 解密     = 竟然成功了，说明 AAD 没生效");
        } catch (GeneralSecurityException ex) {
            System.out.println("换 AAD 解密     = 失败（密文不能被挪到别的任务上，符合预期）");
        }

        try {
            cipher.decrypt("v2.AAAA.BBBB", AAD_MODEL_KEY + "task-1");
            System.out.println("未知版本前缀    = 竟然接受了，说明格式校验缺失");
        } catch (IllegalArgumentException ex) {
            System.out.println("未知版本前缀    = 被拒绝（" + ex.getMessage() + "）");
        }
    }

    /**
     * 脱敏输出。生产项目在把密钥写进日志或返回给前端时，保留头尾、中间打码。
     * 只保留前 7 位和后 4 位，是因为 API Key 的前几位通常是厂商前缀（如 sk-），
     * 对排查「用户填的是哪家的 key」有用，而中间部分才是机密。
     */
    static String mask(String raw) {
        if (raw == null || raw.length() <= 11) {
            return "***";
        }
        return raw.substring(0, 7) + "…" + raw.substring(raw.length() - 4);
    }

    /**
     * 摘要。用于「不需要还原、只需要比对」的场景：会话令牌查表、项目密钥去重。
     * 注意它和哈希的区别：SHA-256 快得离谱，所以只能用于高熵数据（随机令牌），
     * 绝不能用它存密码——密码低熵，快哈希等于给暴力破解加速。
     */
    static String digest(String raw) throws GeneralSecurityException {
        var bytes = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(bytes);
    }
}
