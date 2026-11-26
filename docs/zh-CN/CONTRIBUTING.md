# 构建与贡献

[English](../CONTRIBUTING.md) · [文档首页](README.md)

## 目录结构

- `idea-plugin/` 是 Kotlin IDEA 插件的 Gradle 项目，包含 Wrapper、构建配置和 Java 集成测试启动器。
- `cli/` 是独立的 Go 模块，包含 `go.mod`、命令源码和单元测试。
- `integration-tests/` 的 Python 脚本覆盖真实 IDEA、CLI 和发布安装脚本。
- `benchmarks/` 存放性能测试脚本，`docs/` 存放说明和发布文档。

## IDEA 插件

在仓库根目录用 JDK 21 和模块自带的 Wrapper 执行。用 IDEA 导入 Gradle 项目时打开 `idea-plugin/`。

```sh
./idea-plugin/gradlew -p idea-plugin buildPlugin compileTestJava verifyPlugin
```

Gradle 默认获取 IntelliJ IDEA 2026.1.1。用 `-PideaHome` 可指定本机 IDEA。报告写入 `idea-plugin/build/reports/pluginVerifier/`，安装包是 `idea-plugin/build/distributions/ij-format-mcp-<version>.zip`。

Gradle 从 JitPack 获取固定版本的 elastic-thread-pool JAR。若要验证同版本的本地构建，在 elastic-thread-pool 仓库运行 `mvn install`，再给 Gradle 传入 `-PelasticThreadPoolRepository=/本地/Maven/仓库绝对路径`。 [elastic-thread-pool](https://github.com/Yunie9/elastic-thread-pool)

```sh
./idea-plugin/gradlew -p idea-plugin buildPlugin verifyPlugin \
  '-PideaHome=/Applications/IntelliJ IDEA.app'
python3 integration-tests/test-idea-plugin.py --welcome \
  --idea-home '/Applications/IntelliJ IDEA.app/Contents'
```

macOS 测试需要 Python 3.9 或以上版本。脚本使用临时配置和本地端口，在隔离的 IDEA 中运行插件 ZIP。在图形会话下传入 `--welcome` 可测试欢迎页。日志默认写入 `idea-plugin/build/integration/`，测试启动器由脚本单独编译和加载。

## Go CLI

在仓库根目录使用 Go 1.22 或以上版本。

```sh
go -C cli vet ./...
go -C cli test ./...
go -C cli build -trimpath -ldflags='-s -w' -o ../build/ij-format .
```

Windows 上将输出路径改为 `../build/ij-format.exe`。

对插件 ZIP 运行 [CLI 集成测试](CLI.md#集成测试)。如需验证设置页的两个复制按钮都能生成可用配置，在上面的插件测试命令中加 `--cli build/ij-format`。

GitHub Actions 在 Ubuntu、macOS、Windows 上检查 Go CLI，并在 Ubuntu 上补测 Go 1.22。Ubuntu 的插件任务打包并运行 Plugin Verifier，上传通过验收的插件 ZIP 及已生成的报告。

## 发布版本

更新 `idea-plugin/gradle.properties` 里的 `pluginVersion` 和中英文变更日志。CI 通过后，为该提交创建带说明的 `vMAJOR.MINOR.PATCH` 标签并推送。 [中文变更日志](CHANGELOG.md) · [英文变更日志](../CHANGELOG.md)

Actions 先用打包好的 Linux CLI 和插件验证安装脚本，再创建 Release 草稿。草稿包含通过验证的插件 ZIP、6 个 CLI ZIP（macOS、Linux 和 Windows 各有 `amd64` 和 `arm64`）、`install.sh` 和 `SHA256SUMS`。在 macOS 或 Linux 下载草稿附件后，可运行 `python3 integration-tests/test-installer.py --assets /附件目录 --version 版本号` 复测。正式发布后再验证公开下载地址和在线安装。

如果 Release 已存在，工作流会在上传时停止。从这次运行下载 `idea-plugin` 和 `release-cli` 产物，从标签取出 `scripts/install.sh`，为这 8 个文件生成 `SHA256SUMS`，再补传到已有的 Release。

## 问题报告

报告问题时附上复现步骤和能独立复现的最小源码样例，从示例和日志中移除凭据与私有代码。
