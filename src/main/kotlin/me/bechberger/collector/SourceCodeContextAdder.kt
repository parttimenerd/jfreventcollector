package me.bechberger.collector

import java.nio.file.Path
import java.nio.file.Paths
import me.bechberger.collector.xml.EventSourceContext
import me.bechberger.collector.xml.readXmlAs
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.math.min

/**
 * Finds the usage context of every event in the JDK source code
 */
@OptIn(ExperimentalPathApi::class)
class SourceCodeContextAdder(val openJDKFolder: Path, val metadata: me.bechberger.collector.xml.Metadata) {

    data class ContextLines(val file: Path, val lineNumbers: List<Int>, val startLine: Int, val endLine: Int, val content: String)

    data class Line(val line: Int, val content: String) {
        fun startsWith(s: String) = trim().startsWith(s)
        fun startsWithUntrimmed(s: String) = content.startsWith(s)
        fun trim() = content.trim()
        fun matches(re: Regex) = re.containsMatchIn(content)
    }

    inner class EventUsageContext(val event: String, val sourceCodeContext: List<Path>) {

        fun getContextLines(context: Int, maxLines: Int): List<ContextLines> {
            val linesOfContext = mutableListOf<ContextLines>()
            sourceCodeContext.forEach { file ->
                val isJava = file.extension == "java"
                val re = if (isJava) javaEventUsageRegexp(event) else cppEventUsageRegexp(event)
                val allowedLines = maxLines / sourceCodeContext.size
                val rawLines = file.readText().split("\n").mapIndexed() { index, s -> Line(index, s) }

                // skip license header and includes
                val firstProperLine = if (rawLines[0].startsWith("/*"))
                    rawLines.indexOfFirst { it.startsWith("*/") } + 1 else 0
                val lines = rawLines.subList(firstProperLine, rawLines.size)
                    .filter { if (isJava) !it.startsWithUntrimmed("import ") else !it.startsWithUntrimmed("#include ") }

                val eventLinesRaw =
                    lines.mapIndexed { index, s -> if (s.matches(re)) index to s else -1 to s}.filter { it.first != -1 }

                val eventLines = eventLinesRaw.map { it.first }

                val eventLinesInPath = eventLinesRaw.map { it.second.line }

                assert(eventLines.isNotEmpty()) { "No usage of $event found in $file" }

                fun getSubLines(line: Int, maxLines: Int): List<Line> {
                    var start = maxOf(0, line - maxLines / 2)
                    var end = minOf(lines.size, line + maxLines / 2)
                    if ((end - start) < maxLines) {
                        // add more lines from the beginning if there are not enough lines at the end
                        val missingLines = maxLines - (end - start)
                        start = maxOf(0, start - missingLines)
                    }
                    if ((end - start) < maxLines) {
                        val missingLines = maxLines - (end - start)
                        end = minOf(lines.size, end + missingLines)
                    }
                    return lines.subList(start, end)
                }

                val diff = eventLines.last() - eventLines.first()
                val contextsForThisPath = eventLines.map { getSubLines(it, min(allowedLines / eventLines.size, context)) }

                // now merge overlapping contexts
                val mergedContexts = mutableListOf<ContextLines>()
                var currentContext = contextsForThisPath.first()

                fun addCurrentContextToMerged() {
                    val startLine = currentContext.first().line
                    val endLine = currentContext.last().line
                    val content = currentContext.joinToString("\n") { it.content }
                    mergedContexts.add(ContextLines(file,
                        eventLinesInPath.filter { it in startLine..endLine },
                        startLine, endLine, content))
                }

                for (i in 1 until contextsForThisPath.size) {
                    val nextContext = contextsForThisPath[i]
                    var shouldAdd = i == contextsForThisPath.size - 1
                    if (nextContext.first().line <= currentContext.last().line) {
                        currentContext = currentContext + nextContext.filter { it.line > currentContext.last().line }
                    } else {
                        addCurrentContextToMerged()
                        currentContext = nextContext
                    }
                }
                addCurrentContextToMerged()
                // assert that all matched lines are in the context
                assert(mergedContexts.flatMap { it.lineNumbers }.containsAll(eventLinesInPath)) {
                    "Not all matched lines are in the context"
                }
                linesOfContext.addAll(mergedContexts)
            }
            return linesOfContext
        }
    }

    private fun findSourceCodeContextPerEvent(): Map<String, EventUsageContext> {
        val eventNames = metadata.events.map { it.name }
        val contexts = mutableMapOf<String, MutableList<Path>>()
        val cppRes = eventNames.associateWith { cppEventUsageRegexp(it) }
        val javaRes = eventNames.associateWith { javaEventUsageRegexp(it) }
        val files =
            Files.walk(openJDKFolder.resolve("src")).filter { it.extension in listOf("h", "cpp", "c", "hpp", "java") && it.toFile().isFile }
        val forbiddenPathParts = listOf("awt/", "swing/", "AWT", "accessibility/", "jdk.jdi/share/classes/com/sun")
        files.parallel().flatMap { file ->
            if (forbiddenPathParts.any { it in file.toString() }) {
                return@flatMap null
            }
            val text = file.readText()
            eventNames.mapNotNull { event ->
                val re = (if (file.extension == "java") javaRes[event] else cppRes[event])!!
                val name = event.removePrefix("jdk.")
                val basicCheck = if (file.extension == "java") {
                    "${name}Event" in text
                } else {
                    "Event$name" in text
                }
                if (basicCheck) {
                    if (re.containsMatchIn(text)) {
                        event to file
                    } else {
                        null
                    }
                } else {
                    null
                }
            }.stream()
        }.filter { it != null }.forEach { (event, file) ->
            contexts.getOrPut(event) { mutableListOf() }.add(file)
        }
        return contexts.map { (event, context) -> EventUsageContext(event, context) }.associateBy { it.event }
    }

    fun process(contextLines: Int, maxLines: Int): me.bechberger.collector.xml.Metadata {
        val eventContexts = findSourceCodeContextPerEvent()
        for (event in metadata.events) {
            val context = eventContexts[event.name.removePrefix("jdk.")]
            if (context != null) {
                event.context =  context.getContextLines(contextLines, maxLines).map {
                    EventSourceContext().apply {
                        path = openJDKFolder.relativize(it.file).toString()
                        lines = it.lineNumbers
                        startLine = it.startLine
                        endLine = it.endLine
                        snippet = it.content
                    }
                }.toMutableList()
            }
        }
        return metadata
    }

    companion object {
        fun cppEventUsageRegexp(event: String) =
            Regex("(^|[^a-zA-Z_])Event${event.removePrefix("jdk.")}[&*]?[^a-zA-Z0-9_]+")

        fun javaEventUsageRegexp(event: String) =
            Regex("(^|[^a-zA-Z_.])${event.removePrefix("jdk.")}Event")
    }
}

fun main(args: Array<String>) {
    if (args.size < 3 || args.size > 5) {
        println(
            """  
            Usage: SourceCodeContextAdder <path to metadata.xml> <path to OpenJDK source> <path to result xml file> <optional: context lines per match, default 21> <optional: max lines of context, default 500>
        """.trimMargin()
        )
        return
    }
    val metadataPath = Paths.get(args[0])
    val sourcePath = Paths.get(args[1])
    val contextLines = if (args.size > 3) args[3].toInt() else 20
    val maxLines = if (args.size > 4) args[4].toInt() else 500
    val metadata = metadataPath.readXmlAs(me.bechberger.collector.xml.Metadata::class.java)
    val eventAdder = SourceCodeContextAdder(sourcePath, metadata)
    val meta = eventAdder.process(contextLines, maxLines)
    val out = args[2]
    if (out == "-") {
        println(meta)
    } else {
        Files.write(Paths.get(out), meta.toString().toByteArray())
    }
}