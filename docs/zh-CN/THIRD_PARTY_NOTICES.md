# 第三方声明

[English](../THIRD_PARTY_NOTICES.md) · [文档首页](README.md)

仓库中的 Gradle Wrapper（含脚本和 JAR）由 Gradle 生成，按 Apache License 2.0 分发，用于构建本仓库。 [许可证文本](../../idea-plugin/gradle/wrapper/LICENSE) · [Gradle 源码](https://github.com/gradle/gradle/blob/v9.3.0/LICENSE) · [官方 Wrapper 校验和](https://gradle.org/release-checksums/)

插件在用户自己的 IntelliJ IDEA 中运行。插件 ZIP 不含 IntelliJ IDEA、JVM、Java 或 Kotlin 语言插件、Gson 或 Kotlin 运行时库。这些接口与库由运行中的 IDE 提供。

Go CLI 使用 [go-diff v1.4.5](https://github.com/sergi/go-diff/tree/v1.4.5)，版权归 The go-diff Authors © 2012–2016 所有，采用 [MIT 许可证](../../cli/LICENSE-go-diff)。它基于 Google 的 Diff Match Patch，版权归 Google Inc. © 2006 所有，采用 [Apache 2.0 许可证](../../cli/LICENSE-diff-match-patch)。CLI 二进制分发包包含这两份许可证。

CLI 发布归档还以 `LICENSE-Go` 和 `PATENTS-Go` 的名称包含 Go 发行版的许可证和专利声明。
