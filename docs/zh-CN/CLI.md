# ij-format CLI

[English](../CLI.md) · [文档首页](README.md)

CLI 通过本地 Streamable HTTP 把 UTF-8 源码、语言和选区发送给运行中的 IDEA 插件。IDEA 停在欢迎页也能按当前全局代码风格格式化。精确列范围需要插件 0.3.0 或更新版本，行范围和整文件格式化也兼容 0.2.0。

## 安装 CLI

发布版 CLI 可直接运行。 [安装方法、目录和升级](INSTALL.md#安装-cli) · [从源码构建](CONTRIBUTING.md#go-cli)

## 首次连接

先在 IntelliJ IDEA 中安装 ij-format 插件。 [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/36481-ij-format) · [手动安装](INSTALL.md#安装-idea-插件)

启动 IDEA，在 **Settings → Tools → ij-format** 确认状态为 **Running**。用 `ij-format status` 检查连接、MCP 协议版本和支持的范围类型。它无需源文件，输出不包含令牌。

标准 IDEA 配置目录恰好匹配一份配置时，CLI 会自动使用它。如果找到多个配置或你使用自定义目录，点击 **Copy CLI Config Path**，在每次调用时通过 `--config` 指定。也可以传入从 **Copy MCP JSON** 保存的文件。两种文件都包含本机 Bearer 令牌。

```sh
config="/path/to/ij-format-mcp.json"
ij-format status --config "$config"
printf 'class Demo {\n  val n=1\n}\n' | ij-format --config "$config" - --language kotlin --lines 2:2
```

- 找不到配置或无法连接时，在 **Settings → Plugins → Installed** 检查插件是否启用，再确认服务状态显示 **Running**。
- 鉴权失败时，从要使用的 IDEA 实例重新复制当前配置路径或 MCP JSON。

## 格式化源码

对已有文件，用 `--lines` 或 `--range` 格式化改动的范围。用 `--write` 写回，或按需用 `--diff` 预览。

用 `--timeout 3s` 覆盖单份输入的请求时限，支持 1 毫秒到 2 分钟。默认 15 秒，包含等待 HTTP 执行器及 IDEA 格式化的时间。服务繁忙或超时返回退出码 `2`。IDEA 的 **Settings → Tools → ij-format** 可调整默认时限和最大在途请求数。

```sh
ij-format Foo.java --lines 12:38
ij-format Foo.java --range 12:5-14:18
ij-format - --language kotlin --lines 12:38 < Bar.kt
ij-format Foo.java --lines 12:38 --diff
ij-format Foo.java --lines 12:38 --write
ij-format Foo.java Bar.kt --whole-file --check
```

直接运行 `ij-format` 或 `ij-format --help` 查看完整帮助。旧脚本仍可用 `ij-format format ...`。若首个参数是名为 `format` 或 `status` 的文件，写成 `./format` 或 `./status`。

- `--lines FIRST:LAST` 选择包含首尾的行范围，行号从 1 开始。`--whole-file` 用于整个输入，多文件调用必须使用它。空输入的整文件格式化直接成功。
- `--range LINE:COL-LINE:COL` 选择单个输入中的精确范围，起点包含在内，终点排除在外。行列号从 1 开始，每个 Unicode 码点算一列，制表符也算一列。行末字符后一列可用作终点，文件末尾有换行时也可用下一空行的第一列。IDEA 会拒绝越出选区的改动。
- 根据 `.java` 和 `.kt` 自动识别语言。标准输入（`-`）和非标准扩展名的单文件需指定 `--language java|kotlin`。每份输入最多 128 KiB。
- 单个输入默认输出完整的格式化结果。输出模式最多选一种。`--diff` 显示差异。`--check` 列出需要改动的输入，标准输入显示为 `-`。`--write` 写回发生改动的普通文件，并保留 Unix 权限位。处理多个文件时必须选择输出模式，CLI 按各文件的扩展名识别语言。
- 退出码为 `0`（成功）、`1`（`--check` 发现需要改动的文件）、`2`（参数、文件、连接或格式化失败）。错误写到 stderr。批次会按顺序继续处理其他输入，任何输入失败时批次返回 `2`。

一个批次只握手一次，顺序处理输入，并在服务器保持连接时复用 HTTP 连接。连旧版插件时，每个 HTTP 请求最多等待 25 秒。连接 0.4.0 时，格式化请求的客户端等待时限是服务端允许的最大时限加 5 秒。显式指定 `--timeout` 时，按指定时限加 5 秒。

## Docker 开发容器（实验性）

在宿主机的 IDEA 中添加自定义 VM 参数，然后重启 IDEA。

- macOS Docker Desktop 先使用 `-Dijformat.mcp.container.bind=127.0.0.1`。
- Linux Docker Engine 使用宿主机 Docker 网桥的私有 IPv4 地址。启动容器时加 `--add-host=host.docker.internal:host-gateway`，确认它解析到对应网桥。

在 IDEA 使用 **Copy MCP JSON**，将配置副本中 URL 的主机改为 `host.docker.internal`，保留端口和令牌。将副本只读挂载到容器，避免写入代码仓库或镜像。MCP 客户端可直接使用该配置。CLI 运行 `ij-format status --container --config /run/secrets/ij-format.json` 验证连接，格式化时也带上这两个参数。插件保留宿主机回环端点，两种连接都需要令牌。请只用于可信的本机开发容器。

## 集成测试

在 macOS 上[构建 CLI](CONTRIBUTING.md#go-cli) 和[插件 ZIP](CONTRIBUTING.md#idea-插件)，再在图形会话中用 Python 3.9 或以上版本运行测试。

```sh
python3 integration-tests/test-cli.py --idea-home '/Applications/IntelliJ IDEA.app/Contents'
```

脚本在已打开项目和欢迎页两种模式下启动隔离 IDEA，并验证 100 文件批次的握手次数和 TCP 连接复用。`--cli` 可指定其他路径的二进制。测试默认预期支持精确范围。验证已有的 0.2.0 ZIP 时，传入 `--archive /path/to/ij-format-mcp-0.2.0.zip --no-exact-range`。
