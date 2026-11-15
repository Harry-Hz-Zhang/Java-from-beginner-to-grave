package com.harry.lab.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS 允许的前端来源，对应生产的 config/CorsProperties。
 *
 * <p>第一，来源写死在代码里，换环境就要改代码重新打包，所以生产把它挪进配置，
 * 由 application.yaml 的 {@code lab.cors.allowed-origins} 提供。
 *
 * <p>第二，用 List 而不是单个 String，因为浏览器来源可以不止一个，
 * 并且配置项天然支持逗号分隔的写法。
 */
@ConfigurationProperties("lab.cors")
public record CorsProperties(List<String> allowedOrigins) {}
