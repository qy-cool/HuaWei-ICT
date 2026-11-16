# 独立 Java 符号查询

每次查询启动一个 JVM，用 IntelliJ Java PSI 得到类名、方法签名、文件、行号及源码 SHA-256。包名和方法重载由 PSI 解析。当前从 Maven Central 的 JetBrains kotlin-compiler 2.0.21 取得 com.intellij 实现。查阅本地 IntelliJ Community 的 standalone-analysis 时，官方两个聚合仓库元数据地址当次返回 404；此适配器使用可固定下载的发布物。该发布物较本地参考源码早，语法支持取决于实际版本。

在本目录运行 `mvn -B package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt`。用 Java 21 运行 org.cloide.analysis.Symbols，classpath 为 target/classes 加上生成文件中的条目。多源码根命令接受工作区、全限定类名（可加 #方法名）和一个或多个源码根相对路径。示例输入为仓库根、org.cloide.orders.Orders#transition、examples/orders/api/src/main/java、server/src/main/java。查询路径对应当前文件内容，请将源码摘要与任务提交或差异一起保存。

为编码节点的项目策略增加可选 javaAnalysis 对象。java 指向固定的 JVM 可执行文件，classpath 是管理员安装的绝对路径数组。Pi 工具 query_java_symbols 要求指定已授权文件与类/方法，只将当前文件内容传入分析进程。单文件模式不给分析进程工作区路径。未安装分析器时仍可使用现有源码读取工具。
