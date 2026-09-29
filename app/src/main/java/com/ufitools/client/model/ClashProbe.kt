package com.ufitools.client.model

/**
 * 从设备上「反查」mihomo external-controller 的连接参数。
 *
 * 动机：用户在 App 里第一次填面板地址与 secret 时，通常并不知道
 * secret 是什么（内核启动时由配置文件或插件随机/固定生成）。
 * 与其让用户 SSH 上去翻 yaml，不如直接读出来替用户填好。
 *
 * ⚠️ 本文件**只做纯字符串解析**，不碰 shell、不碰网络——
 * 命令由 [clashConfigProbeCmd] 生成，输出交给 [parseClashConfigProbe]。
 * 这样解析逻辑可以被单测覆盖，也不会因为设备固件差异而编译不过。
 *
 * ## 为什么走 shell 而不是 HTTP
 *
 * 面板未配置时走不了 HTTP（正是要查参数才能连）；设备固件也没有暴露
 * mihomo 配置的 goform 接口。`/api/run_shell` 是唯一可行通道，
 * 与终端流量统计用的是同一条（见 [IW_STATION_DUMP_CMD] 的说明）。
 */
object ClashProbe {

    /**
     * 设备上 mihomo 配置文件的候选路径，按优先级排列。
     *
     * 覆盖三类场景：
     * 1. 小小小猫 / U60Proxy 类插件：数据目录固定在 `/data/plugins/U60Proxy`；
     * 2. 手工部署：常见的 `/data/mihomo`、`/data/clash`；
     * 3. 内置固件：`/etc/mihomo`、`/usr/share/mihomo`。
     *
     * 之所以列而不是猜一个：不同固件/安装方式差异很大，而 `cat` 一个
     * 不存在的路径几乎没有代价，遍历一遍比让用户自己找更省事。
     */
    val CONFIG_CANDIDATES: List<String> = listOf(
        "/data/plugins/U60Proxy/mihomo/config.yaml",
        "/data/plugins/U60Proxy/config.yaml",
        "/data/mihomo/config.yaml",
        "/data/clash/config.yaml",
        "/data/adb/mihomo/config.yaml",
        "/etc/mihomo/config.yaml",
        "/etc/clash/config.yaml",
    )

    /**
     * 生成「找出 mihomo 配置文件并回读连接参数」的 shell 命令。
     *
     * 输出被刻意设计成**逐行 key=value**，且每行都以固定前缀开头，
     * 便于在 [parseClashConfigProbe] 里用最笨的字符串拆分处理——
     * 设备端 busybox 的 `grep`/`sed` 版本差异很大，正则能力不可靠，
     * 因此命令里只用最基本的能力（`cat`、`grep -E`、`ls`）。
     *
     * 同时回读 `external-controller` 的监听地址：绑 `127.0.0.1` 时
     * 手机根本连不上，这个信息必须让用户看到（对应小小小猫的
     * 「回环地址兜底」逻辑）。
     */
    fun clashConfigProbeCmd(): String = buildString {
        append("CFG=''; ")
        // 依次尝试候选路径，第一个「存在且非空」的即为目标
        for (p in CONFIG_CANDIDATES) {
            append("if [ -z \"\$CFG\" ] && [ -f $p ]; then CFG=$p; fi; ")
        }
        append("if [ -z \"\$CFG\" ]; then echo CFGPATH=; echo CFGDONE=1; exit 0; fi; ")
        append("echo CFGPATH=\$CFG; ")
        // secret：可能是 "xxx" / 'xxx' / xxx，统一剥引号
        append("S=\$(grep -E '^secret[[:space:]]*:' \$CFG | head -1 | cut -d: -f2-); ")
        append("S=\$(echo \$S | sed \"s/^[[:space:]]*//; s/[[:space:]]*\$//\"); ")
        append("echo SECRET=\$S; ")
        // external-controller: host:port（可能是 :9090 的省略写法）
        append("E=\$(grep -E '^external-controller[[:space:]]*:' \$CFG | head -1 | cut -d: -f2-); ")
        append("E=\$(echo \$E | sed \"s/^[[:space:]]*//; s/[[:space:]]*\$//\"); ")
        append("echo CONTROLLER=\$E; ")
        // external-ui / external-ui-name：判断面板是否已经装好
        append("U=\$(grep -E '^external-ui[[:space:]]*:' \$CFG | head -1 | cut -d: -f2-); ")
        append("echo EXTERNALUI=\$(echo \$U | sed \"s/^[[:space:]]*//; s/[[:space:]]*\$//\"); ")
        append("N=\$(grep -E '^external-ui-name[[:space:]]*:' \$CFG | head -1 | cut -d: -f2-); ")
        append("echo EXTERNALUINAME=\$(echo \$N | sed \"s/^[[:space:]]*//; s/[[:space:]]*\$//\"); ")
        append("echo CFGDONE=1")
    }
}

/**
 * 设备回读结果。
 *
 * 所有字段都可能为空——固件差异、文件不存在、字段未配置都会导致空值，
 * 因此界面必须能接受「只读到一部分」的情况，不能假设任一字段可用。
 *
 * @param configPath 实际命中的配置文件路径；空串表示没找到
 * @param secret     `secret` 字段原值（可能为空）
 * @param controller `external-controller` 原值，如 `0.0.0.0:9090` / `:9090` / `127.0.0.1:9090`
 * @param externalUi `external-ui` 值，如 `ui`
 * @param externalUiName `external-ui-name` 值，如 `zashboard`
 */
data class ClashConfigProbe(
    val configPath: String = "",
    val secret: String = "",
    val controller: String = "",
    val externalUi: String = "",
    val externalUiName: String = "",
) {
    /** 是否真的找到了配置文件 */
    val found: Boolean get() = configPath.isNotBlank()

    /**
     * 从 `external-controller` 里解析出端口。
     *
     * 三种写法都要支持：
     * - `0.0.0.0:9090` / `127.0.0.1:9090` → 取冒号后
     * - `:9090`（mihomo 里表示监听所有网卡）→ 取冒号后
     * - 无冒号（裸端口或异常值）→ 整个当端口，非法则由调用方兜底
     */
    val port: String
        get() {
            val v = controller.trim().removeSurrounding("\"").removeSurrounding("'")
            if (v.isEmpty()) return ""
            val i = v.lastIndexOf(':')
            val raw = if (i >= 0) v.substring(i + 1).trim() else v
            return raw.filter { it.isDigit() }
        }

    /**
     * 监听地址是否为回环（手机连不上）。
     *
     * 只认明确写成回环的三种；空串（`external-controller: :9090` 这种
     * 省略写法）表示监听所有网卡，**不算**回环——误判会让用户白改一次配置。
     */
    val loopbackOnly: Boolean
        get() {
            val host = controller.trim()
                .removeSurrounding("\"").removeSurrounding("'")
                .substringBeforeLast(':', "")
                .trim()
            return host == "127.0.0.1" || host.equals("localhost", ignoreCase = true) || host == "::1"
        }

    /** 是否已经装过 Web 面板（用于提示「已装 zashboard」之类） */
    val hasPanel: Boolean get() = externalUi.isNotBlank()
}

/**
 * 解析 [ClashProbe.clashConfigProbeCmd] 的输出。
 *
 * 之所以手写解析而不是引 JSON：设备端拼 JSON 需要正确的转义，
 * 而 secret 里出现引号是完全合法的（小小小猫就专门处理了这种情况），
 * 拼 JSON 时转义出错的排查成本远高于收益。
 *
 * 未知行一律忽略：固件上多打印了警告或空行都不该让解析失败。
 */
fun parseClashConfigProbe(output: String?): ClashConfigProbe {
    if (output.isNullOrBlank()) return ClashConfigProbe()
    var path = ""
    var secret = ""
    var controller = ""
    var extUi = ""
    var extUiName = ""
    for (rawLine in output.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val idx = line.indexOf('=')
        if (idx <= 0) continue
        val key = line.substring(0, idx)
        val value = line.substring(idx + 1).trim().let { stripQuotes(it) }
        when (key) {
            "CFGPATH" -> path = value
            "SECRET" -> secret = value
            "CONTROLLER" -> controller = value
            "EXTERNALUI" -> extUi = value
            "EXTERNALUINAME" -> extUiName = value
        }
    }
    return ClashConfigProbe(
        configPath = path,
        secret = secret,
        controller = controller,
        externalUi = extUi,
        externalUiName = extUiName,
    )
}

/** 剥掉 yaml 里的成对引号；单边引号（解析残留）也一并去掉 */
private fun stripQuotes(v: String): String {
    var s = v
    if (s.length >= 2) {
        val first = s.first()
        val last = s.last()
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return s.substring(1, s.length - 1)
        }
    }
    // sed 对单边引号处理不一致，这里兜底：去掉行首尾的孤立引号
    s = s.removePrefix("\"").removePrefix("'")
    s = s.removeSuffix("\"").removeSuffix("'")
    return s
}
