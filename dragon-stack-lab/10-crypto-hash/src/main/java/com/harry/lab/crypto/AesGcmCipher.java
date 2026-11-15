package com.harry.lab.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 演示二：AES-256-GCM 加解密。这份实现对应生产项目的 TaskSecretCipher，
 * 它保护的字段是 task 表里的 MCP 配置和模型 API Key。
 *
 * <p>【为什么用 GCM 而不是 CBC】GCM 是「认证加密」，它在解密时会校验密文是否被篡改，
 * 篡改过就直接抛异常。CBC 只加密不认证，攻击者可以改动密文让你解出错误内容而不报错。
 * 新项目一律用 GCM，CBC 只在对接老系统时才会遇到。
 *
 * <p>【256 位密钥从哪来】构造函数接收一个 Base64 字符串，解码后必须正好 32 字节。
 * 生产项目的配置项 project-api.key-encryption-secret 就是这个值，
 * 它本身放在配置文件里，所以配置文件本身就是最高机密，绝不能进代码仓库。
 * 正式环境应该从环境变量或密钥管理服务注入。
 *
 * <p>【nonce 是什么，为什么每次都要换】12 字节的随机数，保证同样的明文每次加密得到不同密文。
 * 如果 nonce 重复使用，GCM 的安全性会被彻底破坏，攻击者能直接还原明文。
 * 所以这里在每次 encrypt 时都用 SecureRandom 重新生成。
 * 注意 SecureRandom 是线程安全的，可以做成静态字段共用。
 *
 * <p>【AAD 是什么，为什么它是关键设计】附加认证数据，它本身不加密，但会参与完整性校验。
 * 生产项目用「用途前缀 + 归属 uuid」当 AAD，例如 svc-model-key:v1:task-1。
 * 效果是：把任务 1 的密文复制到任务 2 那一行，解密时 AAD 不匹配，直接失败。
 * 这挡住了「有数据库写权限的人把别人密钥挪给自己用」这类攻击。
 *
 * <p>【为什么密文要带 v1 前缀】密钥轮换时，新密文用 v2 格式，解密时按前缀选对应算法，
 * 老数据不用一次性全部重加密。这个前缀只有几字节，却省掉了未来的大麻烦。
 *
 * <p>【生产项目其实只认 v1】这点必须说清楚，否则容易误以为轮换已经实现了。
 * 生产 TaskSecretCipher 的解密里是：不是 "v1" 就一律抛异常（fail-closed）。
 * 也就是说前缀是「为将来留的位置」，当前实现是「只认一个版本且严格拒绝其它版本」。
 * 这个设计比「不认识就当前缀不存在、硬解一遍」安全得多——后者会让攻击者
 * 有办法诱导你用一个更弱的算法去解密。
 *
 * <p>【解开一段密文前必须校验三件事】段数是不是 3、版本前缀是不是认识的字面量、
 * nonce 长度是不是 12。三件事任何一件不满足都要立刻拒绝，不能「先 try 一下」。
 * 生产实现就是因为做了这三步校验，畸形输入才会得到一句明确的错误而不是数组越界。
 */
public class AesGcmCipher {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 认识且唯一支持的密文格式版本。追加 v2 时这里要变成 Set 并按段选算法。 */
    private static final String VERSION = "v1";

    private final SecretKeySpec key;

    public AesGcmCipher(String base64Key) {
        var bytes = Base64.getDecoder().decode(base64Key);
        // 在构造时就校验密钥长度，而不是等第一次加密才抛 InvalidKeyException。
        // 这是 fail-closed：配置错了，应用根本起不来，而不是上线后某个接口偶发 500。
        // AES-256 的密钥必须正好 32 字节；16 字节是 AES-128，24 字节是 AES-192。
        if (bytes.length != 32) {
            throw new IllegalStateException("AES-256 密钥必须是 32 字节，当前 " + bytes.length + " 字节");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String plaintext, String aad) throws GeneralSecurityException {
        var nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        var sealed = cipher(Cipher.ENCRYPT_MODE, nonce, aad)
                .doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return VERSION + "." + encoder.encodeToString(nonce) + "." + encoder.encodeToString(sealed);
    }

    public String decrypt(String value, String aad) throws GeneralSecurityException {
        var parts = value.split("\\.", -1);
        // 三段校验必须全部通过才继续。少一段就是畸形数据，
        // 不做这层校验的话，parts[1] 会抛 ArrayIndexOutOfBoundsException——
        // 那是「程序 bug」的信号，而实际是「数据脏」，两者排查方向完全不同。
        if (parts.length != 3) {
            throw new IllegalArgumentException("密文格式非法，应为 v1.nonce.密文 三段");
        }
        if (!VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("不认识的密文版本 " + parts[0]);
        }
        var nonce = Base64.getUrlDecoder().decode(parts[1]);
        if (nonce.length != 12) {
            throw new IllegalArgumentException("nonce 长度必须是 12，当前 " + nonce.length);
        }
        var plain = cipher(Cipher.DECRYPT_MODE, nonce, aad)
                .doFinal(Base64.getUrlDecoder().decode(parts[2]));
        return new String(plain, StandardCharsets.UTF_8);
    }

    private Cipher cipher(int mode, byte[] nonce, String aad) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // 128 是认证标签的位数，单位是 bit。GCM 的标准取值就是 128，不要改小。
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
