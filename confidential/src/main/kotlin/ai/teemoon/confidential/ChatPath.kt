// ports: teemoon/Confidential/ChatPath.swift @ 71f83846 (routers followed breadth-first to the engine, depth 4)
package ai.teemoon.confidential

/**
 * Which running services a chat's request passes through on a CVM, per the HOST the app connected to (audit
 * 2026-11-06, conformance/nearai-trust expected.json chat_path): nginx's server_name → its `location /` proxy_pass →
 * the proxy's VLLM_BACKEND_URLS / VLLM_BASE_URL, through routers. Never per model id: two proxies on one node can declare the same
 * MODEL_NAME. Anything it can't pin down is [Result.Ambiguous], and the caller then counts every LEAKS page on the CVM.
 */
object ChatPath {
    data class Hop(
        val service: String,
        val source: DeployLog.Source,
        /** The image the service runs: its own `image:`, or a build's last FROM. */
        val image: ImageRef?,
    )

    sealed interface Result {
        data class Path(val hops: List<Hop>) : Result
        data class Ambiguous(val why: String) : Result

        val services: Set<String>? get() = (this as? Path)?.hops?.map { it.service }?.toSet()
    }

    fun resolve(host: String, running: List<RunningService>, body: (DeployLog.Source) -> String?): Result {
        val bySource = HashMap<String, DeployLog.Source>()
        for (r in running) if (r.service.isNotEmpty()) sourceOf(r)?.let { bySource[r.service] = it }
        val h = host.lowercase()

        // (1) Ingress: a running service with published ports whose mounted nginx config serves HOST.
        val matches = ArrayList<Pair<String, String>>()   // ingress → target
        for (source in bySource.values.toSet()) {
            val text = body(source) ?: return Result.Ambiguous("a running revision's file is unread")
            val compose = Compose(text)
            for ((name, block) in compose.services) {
                if (bySource[name] != source || !block.hasPorts) continue
                for (config in block.configSources) {
                    val conf = compose.configs[config] ?: continue
                    Nginx.proxyTarget(conf, h)?.let { matches += name to it }
                }
            }
        }
        val first = matches.firstOrNull() ?: return Result.Ambiguous("no server_name matches $h")
        if (!matches.all { it == first }) return Result.Ambiguous("several server blocks match $h")
        val proxy = serviceName(first.second) ?: return Result.Ambiguous("unresolvable proxy_pass")

        // (2) Engines: each hop's backend list (a literal or a ${VAR:-literal} default), followed through routers (3) to
        // an engine, breadth-first; every hop wears its OWN attributing revision and the image it runs (iOS 71f83846).
        val (ingress, _) = hop(first.first, bySource, body) ?: return Result.Ambiguous("${first.first} has no attributed revision")
        val hops = arrayListOf(ingress)
        val seen = hashSetOf(first.first, proxy)
        var frontier = listOf(proxy)
        var depth = 1
        while (frontier.isNotEmpty()) {
            val next = ArrayList<String>()
            for (name in frontier) {
                val (h, backends) = hop(name, bySource, body) ?: return Result.Ambiguous("$name has no attributed revision")
                hops += h
                when (backends) {
                    Compose.Backends.None -> if (name == proxy) return Result.Ambiguous("the proxy has no backend list")
                    Compose.Backends.Unreadable -> return Result.Ambiguous("$name's backend list isn't a literal")
                    is Compose.Backends.Urls -> {
                        val upstream = backends.urls.mapNotNull(::serviceName)
                        if (upstream.isEmpty() || upstream.size != backends.urls.size) return Result.Ambiguous("unreadable backend URL")
                        next += upstream.filter { seen.add(it) }
                    }
                }
            }
            if (next.isNotEmpty()) depth += 1
            if (depth > MAX_DEPTH) return Result.Ambiguous("more than $MAX_DEPTH hops")
            frontier = next
        }
        return Result.Path(hops)
    }

    /** Edges from the ingress to the deepest engine; a longer chain is ambiguous. */
    const val MAX_DEPTH = 4

    private fun hop(name: String, bySource: Map<String, DeployLog.Source>, body: (DeployLog.Source) -> String?): Pair<Hop, Compose.Backends>? {
        val source = bySource[name] ?: return null
        val block = body(source)?.let { Compose(it).services[name] } ?: return null
        return Hop(name, source, block.image) to block.backends
    }

    /** A running row's (commit, file, sha), when the walk attributed it. */
    fun sourceOf(r: RunningService): DeployLog.Source? {
        val commit = r.commit ?: return null; val file = r.file ?: return null
        return DeployLog.Source(commit, file, r.fileSHA256 ?: "")
    }

    /**
     * A backend URL's host: the compose service it names ("http(s)://<service>:<port>/…" → "<service>"); nothing here
     * is contacted, the URL is read from the signed recipe. Read by pattern, not java.net.URI: URI gives no host for a
     * name with an underscore, which compose service names may carry and iOS's URLComponents accepts.
     */
    fun serviceName(url: String): String? =
        Regex("^(?:http|https)://([^/:?#\\s]+)").find(url.trim())?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    // MARK: Compose, as far as the path needs it

    class Compose(input: String) {
        /** A hop's upstreams: none (an engine), an override the log doesn't record, or literal URLs. */
        sealed interface Backends {
            data object None : Backends
            data object Unreadable : Backends
            data class Urls(val urls: List<String>) : Backends
        }

        class Service(val text: String) {
            val hasPorts: Boolean get() = Regex("(?m)^\\s+ports:").containsMatchIn(text)
            val configSources: List<String> get() = captures("(?m)^\\s*-\\s*source:\\s*([A-Za-z0-9_.-]+)", text)
            /** VLLM_BACKEND_URLS, else VLLM_BASE_URL; [Backends.Unreadable] when only an unrecorded override. */
            val backends: Backends get() {
                for (key in listOf("VLLM_BACKEND_URLS", "VLLM_BASE_URL")) {
                    val raw = envValue(key) ?: continue
                    val value = literal(raw) ?: return Backends.Unreadable
                    return Backends.Urls(value.split(",").map { it.trimSpaces() })
                }
                return Backends.None
            }
            val backendURLs: List<String>? get() = (backends as? Backends.Urls)?.urls
            val image: ImageRef? get() {
                val lines = text.split("\n")
                lines.firstOrNull { it.trimSpaces().startsWith("image:") }?.let { line ->
                    ProvenanceParsing.imageRefs(line).firstOrNull()?.let { return it }
                }
                val froms = lines.filter { it.trimSpaces().uppercase().startsWith("FROM ") }
                return froms.lastOrNull()?.let { ProvenanceParsing.imageRefs(it).firstOrNull() }
            }
            /** The MODEL_NAME a proxy declares, when it's a literal. */
            val modelName: String? get() = envValue("MODEL_NAME")?.let(::literal)
            private fun envValue(key: String): String? =
                captures("(?m)^\\s*(?:-\\s*)?$key\\s*[=:]\\s*(.+?)\\s*$", text).firstOrNull()?.trim { it == '"' || it == '\'' }
            /** "x" → x; "${VAR:-x}" → x; "${VAR}" → null (an override the log doesn't record). */
            private fun literal(value: String): String? {
                if ("\${" !in value) return value
                if (!Regex("^\\$\\{[A-Za-z0-9_]+:-(.*)\\}$").matches(value)) return null
                val v = value.substring(value.indexOf(":-") + 2).dropLast(1)
                return if ("\${" in v) null else v
            }
        }

        val services: Map<String, Service>
        val configs: Map<String, String>

        init {
            val yaml = PlaintextExposure.dockerComposeYaml(input)
            val anchors = PlaintextExposure.anchorBlocks(yaml)
            val out = HashMap<String, Service>()
            for (block in PlaintextExposure.serviceBlocks(yaml)) {
                val header = block.substringBefore("\n").trimSpaces()
                if (!header.endsWith(":")) continue
                out[header.dropLast(1)] = Service(expandFully(block, anchors))
            }
            services = out
            configs = configContents(yaml)
        }

        companion object {
            /** Anchors can merge anchors: expand until nothing new appears. */
            fun expandFully(block: String, anchors: Map<String, String>): String {
                var text = block
                repeat(8) {
                    val next = PlaintextExposure.expand(text, anchors)
                    if (next == text) return text
                    text = next
                }
                return text
            }

            /** Top-level `configs:` → name → its `content: |` text, compose's `$$` unescaped. */
            fun configContents(yaml: String): Map<String, String> {
                val lines = yaml.split("\n")
                val start = lines.indexOfFirst { it.startsWith("configs:") }
                if (start < 0) return emptyMap()
                val out = HashMap<String, String>()
                var name: String? = null
                val body = ArrayList<String>()
                var contentIndent: Int? = null
                fun flush() {
                    val n = name
                    if (n != null && body.isNotEmpty()) out[n] = body.joinToString("\n").replace("$$", "$")
                    body.clear(); contentIndent = null
                }
                for (line in lines.drop(start + 1)) {
                    val indent = line.takeWhile { it == ' ' }.length
                    val trimmed = line.trimSpaces()
                    if (trimmed.isNotEmpty() && indent == 0) break
                    if (indent == 2 && trimmed.endsWith(":") && !trimmed.startsWith("#")) {
                        flush(); name = trimmed.dropLast(1); continue
                    }
                    val ci = contentIndent
                    if (ci == null) {
                        if (trimmed.startsWith("content:")) contentIndent = indent + 2
                        continue
                    }
                    if (trimmed.isEmpty() || indent >= ci) body += line.drop(minOf(ci, indent))
                }
                flush()
                return out
            }
        }
    }

    // MARK: nginx, as far as the path needs it

    object Nginx {
        /** The `location /` target of the server block whose server_name matches [host], if one does. */
        fun proxyTarget(conf: String, host: String): String? {
            for (server in blocks("server", conf)) {
                if (serverNames(server).none { matches(it, host) }) continue
                for (location in blocks("location /", server)) {
                    val pass = directive("proxy_pass", location) ?: continue
                    if (pass.startsWith("$")) {
                        val set = captures("set\\s+${Regex.escape(pass)}\\s+([^;\\s]+)\\s*;", location + "\n" + server)
                        return set.firstOrNull()
                    }
                    return pass
                }
            }
            return null
        }

        /**
         * The `{…}` bodies of every `name {` at any depth, braces balanced. "location /" matches only the bare prefix
         * location, never `location ^~ /x` or `location = /x`.
         */
        fun blocks(name: String, text: String): List<String> {
            val re = Regex("(?m)(^|[;{\\s])" + name.split(" ").joinToString("\\s+") { Regex.escape(it) } + "\\s*\\{")
            val out = ArrayList<String>()
            for (m in re.findAll(text)) {
                val open = m.range.last
                var depth = 0
                var i = open
                while (i < text.length) {
                    val c = text[i]
                    if (c == '{') depth++ else if (c == '}') { depth--; if (depth == 0) break }
                    i++
                }
                if (i >= text.length) continue
                out += text.substring(open + 1, i)
            }
            return out
        }

        fun serverNames(server: String): List<String> =
            directive("server_name", server, topLevelOnly = true)?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()

        fun matches(name: String, host: String): Boolean {
            if (name.startsWith("~")) {
                val re = runCatching { Regex(name.drop(1).trimSpaces(), RegexOption.IGNORE_CASE) }.getOrNull() ?: return false
                return re.containsMatchIn(host)
            }
            return name.lowercase() == host
        }

        /** A directive's value up to its `;`, comments dropped, across lines. */
        fun directive(name: String, block: String, topLevelOnly: Boolean = false): String? {
            var text = block.split("\n").joinToString("\n") { line -> line.substringBefore("#") }
            if (topLevelOnly) text = stripNestedBlocks(text)
            return captures("(?:^|[;{\\s])$name\\s+([^;]+);", text).firstOrNull()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ")
        }

        private fun stripNestedBlocks(text: String): String {
            val out = StringBuilder(); var depth = 0
            for (c in text) {
                if (c == '{') { depth++; continue }
                if (c == '}') { depth--; continue }
                if (depth == 0) out.append(c)
            }
            return out.toString()
        }
    }

    /** Group 1 of every match of [pattern] in [text]. */
    fun captures(pattern: String, text: String): List<String> {
        val re = runCatching { Regex(pattern) }.getOrNull() ?: return emptyList()
        return re.findAll(text).mapNotNull { it.groups[1]?.value }.toList()
    }

    private fun String.trimSpaces() = trim { it == ' ' || it == '\t' }
}
