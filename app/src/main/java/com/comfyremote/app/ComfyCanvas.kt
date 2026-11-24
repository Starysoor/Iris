package com.comfyremote.app

import org.json.JSONArray
import org.json.JSONObject

/** Resolves links from canvas workflows before converting their inputs to an API prompt. */
internal class ComfyCanvas(workflow: JSONObject) {
    sealed interface Input {
        data class Link(val nodeId: String, val output: Int) : Input
        data class Value(val value: Any?) : Input
        data class Unresolved(val reason: String) : Input
    }

    private data class Edge(
        val origin: String,
        val output: Int,
        val target: String,
        val input: Int,
    )

    private val definitions = linkedMapOf<String, JSONObject>()
    private val root: Graph

    init {
        fun register(graph: JSONObject) {
            graph.optJSONObject("definitions")?.optJSONArray("subgraphs").objects().forEach { definition ->
                val id = definition.optString("id")
                if (id.isNotBlank() && definitions.putIfAbsent(id, definition) == null) register(definition)
            }
        }
        register(workflow)
        root = Graph(workflow, null, emptySet())
    }

    val problems = mutableListOf<String>()

    fun executableNodes(): List<Node> = buildList {
        fun visit(graph: Graph) {
            for (node in graph.nodes.values) {
                if (node.mode == MUTED || node.mode == BYPASSED) continue
                if (node.type in definitions) {
                    val nested = node.subgraph
                    if (nested == null) {
                        problems += "无法展开子图 #${node.id}（${node.type}）"
                    } else {
                        node.promotedValueProblem?.let(problems::add)
                        visit(nested)
                    }
                } else {
                    add(node)
                }
            }
        }
        visit(root)
    }

    internal inner class Graph(
        val raw: JSONObject,
        val host: Node?,
        val ancestors: Set<String>,
    ) {
        val nodes = raw.optJSONArray("nodes").objects().associate { it.optString("id") to Node(this, it) }
        val inputId = raw.optJSONObject("inputNode")?.optString("id")
        val outputId = raw.optJSONObject("outputNode")?.optString("id")
        val ports = raw.optJSONArray("inputs").objects()
        val outputPorts = raw.optJSONArray("outputs").objects()
        private val links: Map<String, Edge> = buildMap {
            val entries = raw.optJSONArray("links") ?: return@buildMap
            for (i in 0 until entries.length()) {
                val entry = entries.opt(i)
                when (entry) {
                    is JSONArray -> if (entry.length() >= 5) {
                        put(entry.optString(0), Edge(entry.optString(1), entry.optInt(2),
                            entry.optString(3), entry.optInt(4)))
                    }
                    is JSONObject -> if (entry.has("id") && entry.has("origin_id")) {
                        put(entry.optString("id"), Edge(entry.optString("origin_id"), entry.optInt("origin_slot"),
                            entry.optString("target_id"), entry.optInt("target_slot")))
                    }
                }
            }
        }

        fun resolve(link: String, visited: MutableSet<Pair<Graph, String>>): Input? {
            val edge = links[link] ?: return Input.Unresolved("找不到连线 #$link")
            val key = this to link
            if (!visited.add(key)) return Input.Unresolved("连线存在循环 #$link")
            try {
                if (edge.origin == inputId && host != null) return resolveExternalInput(edge.output, visited)
                val source = nodes[edge.origin] ?: return Input.Unresolved("找不到来源节点 #${edge.origin}")
                if (source.mode == MUTED) return null
                if (source.type == "Reroute" || source.mode == BYPASSED) {
                    val outputType = source.outputs.getOrNull(edge.output)?.optString("type").orEmpty()
                    val candidates = source.inputs.filter { readLink(it) != null }
                    val input = candidates.firstOrNull { outputType.isNotBlank() && it.optString("type") == outputType }
                        ?: if (outputType.isBlank() || outputType == "*") candidates.singleOrNull() else null
                    return readLink(input)?.let { resolve(it, visited) }
                }
                if (source.type == "PrimitiveNode") return Input.Value(source.raw.optJSONArray("widgets_values")?.opt(0))
                if (source.type !in definitions) return Input.Link(source.id, edge.output)
                val nested = source.subgraph ?: return Input.Unresolved("无法展开子图 #${source.id}")
                return nested.resolveOutput(source.outputs.getOrNull(edge.output), edge.output, visited)
            } finally {
                visited.remove(key)
            }
        }

        private fun resolveExternalInput(slot: Int, visited: MutableSet<Pair<Graph, String>>): Input? {
            val outer = host ?: return null
            val port = ports.getOrNull(slot) ?: return Input.Unresolved("子图 #${outer.id} 缺少输入 #$slot")
            val outerPort = findPort(outer.inputs, port)
            val externalLink = readLink(outerPort)
            if (externalLink != null) return outer.graph.resolve(externalLink, visited)
            val name = port.optString("name")
            return outer.promotedValues[name]?.let { Input.Value(it) }
        }

        private fun resolveOutput(outerPort: JSONObject?, slot: Int, visited: MutableSet<Pair<Graph, String>>): Input? {
            val port = outerPort?.let { findPort(outputPorts, it) }
            val index = if (port != null) outputPorts.indexOf(port) else slot
            val link = outputPorts.getOrNull(index)?.optJSONArray("linkIds")?.optString(0)
                ?.takeIf(String::isNotBlank)
                ?: links.entries.singleOrNull { it.value.target == outputId && it.value.input == index }?.key
            return link?.let { resolve(it, visited) } ?: Input.Unresolved("子图 #${host?.id} 的输出未连接")
        }

        fun widgetPorts(): List<String> = ports.mapNotNull { port ->
            val linkIds = port.optJSONArray("linkIds") ?: return@mapNotNull null
            val hasWidgetTarget = (0 until linkIds.length()).any { index ->
                val edge = links[linkIds.optString(index)] ?: return@any false
                val input = nodes[edge.target]?.inputs?.firstOrNull { readLink(it) == linkIds.optString(index) }
                input?.has("widget") == true
            }
            port.optString("name").takeIf { hasWidgetTarget && it.isNotBlank() }
        }
    }

    internal inner class Node internal constructor(val graph: Graph, val raw: JSONObject) {
        private val rawId = raw.optString("id")
        val id: String = graph.host?.let { "${it.id}:$rawId" } ?: rawId
        val type = raw.optString("type")
        val mode = raw.optInt("mode")
        val inputs = raw.optJSONArray("inputs").objects()
        val outputs = raw.optJSONArray("outputs").objects()

        val subgraph: Graph? by lazy {
            val definition = definitions[type]
            if (type in graph.ancestors || definition?.optJSONArray("nodes") == null) null
            else Graph(definition, this, graph.ancestors + type)
        }

        private val proxyWidgets = raw.optJSONObject("properties")?.optJSONArray("proxyWidgets")
        private val widgets = raw.optJSONArray("widgets_values")
        private val promotedNames by lazy {
            inputs.filter { it.has("widget") }.map { it.optString("name") }
                .ifEmpty { subgraph?.widgetPorts().orEmpty() }
        }
        private val hasProxyValues: Boolean
            get() = (widgets?.length() ?: 0) > 0 && proxyWidgets?.length() == widgets?.length()

        val promotedValues: Map<String, Any> by lazy {
            val named = raw.optJSONObject("widgets_values")
            when {
                named != null -> named.keys().asSequence().associateWith { named.get(it) }
                hasProxyValues || widgets == null || widgets.length() != promotedNames.size -> emptyMap()
                else -> promotedNames.mapIndexed { index, name -> name to widgets.get(index) }.toMap()
            }
        }

        val promotedValueProblem: String?
            get() = if ((widgets?.length() ?: 0) > 0 && !hasProxyValues && promotedValues.isEmpty()) {
                "无法完整转换子图 #$id 的提升控件"
            } else null

        fun widgetOverrides(): Map<String, Any> {
            val owner = graph.host?.takeIf { it.hasProxyValues } ?: return emptyMap()
            val refs = owner.proxyWidgets ?: return emptyMap()
            val values = owner.widgets ?: return emptyMap()
            return buildMap {
                for (i in 0 until refs.length()) {
                    val ref = refs.optJSONArray(i) ?: continue
                    if (ref.optString(0) == rawId) put(ref.optString(1), values.get(i))
                }
            }
        }

        fun resolve(input: JSONObject): Input? =
            readLink(input)?.let { graph.resolve(it, mutableSetOf()) }
    }

    private companion object {
        const val MUTED = 2
        const val BYPASSED = 4

        fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

        fun readLink(input: JSONObject?): String? =
            input?.opt("link")?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank)

        fun findPort(ports: List<JSONObject>, port: JSONObject): JSONObject? {
            val id = port.optString("id")
            return ports.firstOrNull { id.isNotBlank() && it.optString("id") == id }
                ?: ports.singleOrNull { it.optString("name") == port.optString("name") }
        }
    }
}
