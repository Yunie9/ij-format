# ij-format CLI

[English](../CLI.md) · [文档首页](README.md)

CLI 连接正在运行的 IDEA 插件的 Streamable HTTP 端点，把本地 UTF-8 源码、语言和选区发送给插件。精确列范围需要插件 0.3.0，行范围和整文件格式化也兼容 0.2.0。

## 安装 CLI

在 GitHub Releases 下载对应操作系统和 CPU 架构的归档，解压后把 `ij-format` 加入 `PATH`。Windows 的可执行文件是 `ij-format.exe`。已编译的程序运行时无需安装 Go、Python 或 Gradle。

开发时可以用 Go 1.22 或以上版本，在仓库根目录从源码构建。

```sh
go -C cli build -trimpath -ldflags='-s -w' -o ../build/ij-format .
```

可直接运行 `./build/ij-format`。在 Windows 上把构建输出改为 `../build/ij-format.exe`。

## 首次连接

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
- 单个输入默认把完整结果写到 stdout。`--diff` 打印统一差异，`--check` 列出需要改动的路径，`--write` 替换修改过的文件并保留权限位。三种模式互斥。多文件必须显式指定一种模式，并根据每个文件的扩展名识别语言。
- 退出码为 `0`（成功）、`1`（`--check` 发现需要改动的文件）、`2`（参数、文件、连接或格式化失败）。错误写到 stderr。批次会按顺序继续处理其他输入，任何输入失败时批次返回 `2`。

一个批次只握手一次，顺序处理输入，并在服务器保持连接时复用 HTTP 连接。如果 Agent 的 shell 禁止访问本地端口，CLI 会报连接权限错误，Agent 可以使用已配置的 MCP 工具。

## 集成测试

用上面的命令构建 CLI，再[构建插件 ZIP](CONTRIBUTING.md#idea-插件)。在 macOS 图形会话中用 Python 3.9 或以上版本运行测试。

```sh
python3 integration-tests/test-cli.py --idea-home '/Applications/IntelliJ IDEA.app/Contents'
```

脚本在已打开项目和欢迎页两种模式下启动隔离 IDEA，并验证 100 文件批次的握手次数和 TCP 连接复用。`--cli` 可指定其他路径的二进制。测试默认预期支持精确范围。验证已有的 0.2.0 ZIP 时，传入 `--archive /path/to/ij-format-mcp-0.2.0.zip --no-exact-range`。
