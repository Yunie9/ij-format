# 构建与贡献

[English](../CONTRIBUTING.md) · [文档首页](README.md)

## 目录结构

- `idea-plugin/` 是 Kotlin IDEA 插件的 Gradle 项目，包含 Wrapper、构建配置和 Java 集成测试启动器。
- `cli/` 是独立的 Go 模块，包含 `go.mod`、命令源码和单元测试。
- `integration-tests/` 的 Python 脚本用真实 IDEA 验证插件 ZIP 和 CLI。
- `benchmarks/` 存放性能测试脚本，`docs/` 存放说明和发布文档。

## IDEA 插件

在仓库根目录用 JDK 21 和模块自带的 Wrapper 执行。用 IDEA 导入 Gradle 项目时打开 `idea-plugin/`。

```sh
./idea-plugin/gradlew -p idea-plugin buildPlugin compileTestJava verifyPlugin
```

Gradle 默认获取 IntelliJ IDEA 2026.1.1。用 `-PideaHome` 可指定本机 IDEA。报告写入 `idea-plugin/build/reports/pluginVerifier/`，安装包是 `idea-plugin/build/distributions/ij-format-mcp-<version>.zip`。

```sh
./idea-plugin/gradlew -p idea-plugin buildPlugin verifyPlugin \
  '-PideaHome=/Applications/IntelliJ IDEA.app'
python3 integration-tests/test-idea-plugin.py --welcome \
  --idea-home '/Applications/IntelliJ IDEA.app/Contents'
```

macOS 集成测试需要 Python 3.9 或以上版本，使用临时配置和回环端口运行隔离的 IDEA。图形会话下传入 `--welcome` 可测试真实欢迎页。日志默认在 `idea-plugin/build/`，测试脚本单独编译集成测试启动器。

## Go CLI

在仓库根目录使用 Go 1.22 或以上版本。

```sh
go -C cli vet ./...
go -C cli test ./...
go -C cli build -trimpath -ldflags='-s -w' -o ../build/ij-format .
```

对插件 ZIP 运行 [CLI 集成测试](CLI.md#集成测试)。如需验证设置页的两个复制按钮都能生成可用配置，在上面的插件测试命令中加 `--cli build/ij-format`。

GitHub Actions 在 Ubuntu、macOS、Windows 上检查 Go CLI，并在 Ubuntu 上补测 Go 1.22。Ubuntu 的插件任务打包并运行 Plugin Verifier，上传通过验收的插件 ZIP 及已生成的报告。

## 发布版本

更新 `pluginVersion` 和中英文[变更日志](CHANGELOG.md)。等发布提交通过 CI 后，为该提交创建带说明的 `vMAJOR.MINOR.PATCH` 标签并推送。

工作流会验证标签对应的源码，再创建 GitHub Release 草稿，上传通过验证的插件 ZIP、macOS、Linux 和 Windows 的 CLI ZIP（各有 `amd64` 和 `arm64`），以及 `SHA256SUMS`。草稿采用对应的英文变更日志条目。发布前检查草稿，并下载插件和 CLI 在目标 IDEA 版本上联测。如果工作流提示版本已存在，在 GitHub 上检查对应的 Release 及其附件。

## 问题报告

保持每次格式化独立于当前项目。报告格式化问题时提供可独立复现的最小样例和具体步骤，并移除凭据与私有源码。
