package me.bechberger.collector

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import me.bechberger.collector.xml.AIGeneratedDescription
import me.bechberger.collector.xml.Event
import me.bechberger.collector.xml.readXmlAs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.streams.asStream


/**
 * Finds the usage context of every event in the JDK source code and adds a generated description
 * based on this context
 *
 * TODO: add support for events declared in Java
 */
class AIDescriptionAdder(val openJDKFolder: Path, val metadata: me.bechberger.collector.xml.Metadata) {

    private fun String.countTokens(): Int {
        val registry: EncodingRegistry = Encodings.newDefaultEncodingRegistry()
        val enc = registry.getEncoding(EncodingType.CL100K_BASE)
        return enc.encode(this).size
    }

    data class ContextLines(val file: Path, val lineNumbers: List<Int>, val content: String)

    inner class EventUsageContext(val event: String, val sourceCodeContext: List<Path>) {
        private fun getLinesOfContext(n: Int): List<ContextLines> {
            val linesOfContext = mutableListOf<ContextLines>()
            sourceCodeContext.forEach { file ->
                val re = if (file.extension == "java") javaEventUsageRegexp(event) else cppEventUsageRegexp(event)
                val allowedLines = n / sourceCodeContext.size
                val rawLines = file.readText().split("\n")
                // skip license header and includes
                val firstProperLine = if (rawLines[0].startsWith("/*"))
                    rawLines.indexOfFirst { it.trim().startsWith("*/") } + 1 else 0
                val lines = rawLines.subList(firstProperLine, rawLines.size)
                    .filter { !it.trim().startsWith("#include ") && !it.trim().startsWith("import ") }
                val eventLines =
                    lines.mapIndexed { index, s -> if (re.containsMatchIn(s)) index else -1 }.filter { it != -1 }

                assert(eventLines.isNotEmpty()) { "No usage of $event found in $file" }

                fun getSubString(line: Int, maxLines: Int): String {
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
                    return lines.subList(start, end).joinToString("\n")
                }

                val diff = eventLines.last() - eventLines.first()
                val content = if (diff > allowedLines * 0.8) {
                    eventLines.joinToString("\n\n//... \n\n") { getSubString(it, allowedLines / eventLines.size) }
                } else {
                    getSubString((eventLines.first() + eventLines.last()) / 2, allowedLines)
                }

                linesOfContext.add(ContextLines(file, eventLines, content))
            }
            return linesOfContext
        }

        private fun getContext(n: Int): String {
            return getLinesOfContext(n).joinToString("\n\n") { "\nfile: ${it.file.relativeTo(openJDKFolder)}\n${it.content}" }
        }

        fun getContextMaxTokens(n: Int): String {
            var lines = n / 16
            while (n < getContext(lines).countTokens()) {
                lines = Math.round(lines * 0.9).toInt()
            }
            return getContext(lines)
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun findSourceCodeContextPerEvent(): Map<String, EventUsageContext> {
        val eventNames = metadata.events.map { it.name }
        val contexts = mutableMapOf<String, MutableList<Path>>()
        val cppRes = eventNames.associateWith { cppEventUsageRegexp(it) }
        val javaRes = eventNames.associateWith { javaEventUsageRegexp(it) }
        val files =
            openJDKFolder.resolve("src").walk().filter { it.extension in listOf("h", "cpp", "c", "hpp", "java") }
        val forbiddenPathParts = listOf("awt/", "swing/", "AWT", "accessibility/", "jdk.jdi/share/classes/com/sun")
        files.asStream().parallel().flatMap { file ->
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

    private fun promptGPT3(prompt: String, apiKey: String, baseUrl: String, model: String = "gpt-35-4k-0613"): String {
        val url = "$baseUrl/openai/deployments/$model/chat/completions?api-version=2023-05-15"
        val requestBody = """
        {"messages": [{"role": "user", "content": ${JSONObject.quote(prompt)}}]}
    """.trimIndent().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("api-key", apiKey)
            .post(requestBody)
            .build()

        val client = OkHttpClient()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response")
        }

        JSONObject(response.body?.string() ?: throw IOException("Empty response body")).let {
            val choices = it.getJSONArray("choices")
            if (choices.length() != 1) {
                throw IOException("Unexpected number of choices: ${choices.length()}")
            }
            val choice = choices.getJSONObject(0)
            val text = choice.getJSONObject("message").getString("content")
            val finishReason = choice.getString("finish_reason")
            if (finishReason != "stop") {
                throw IOException("Unexpected finish reason: $finishReason")
            }
            return text
        }
    }

    private fun promptAI(prompt: String, model: String = "gpt-35-4k-0613"): String? {
        try {
            return promptGPT3(prompt, openAIConfig!!.key, openAIConfig!!.baseURL, model)
        } catch (e: IOException) {
            println("Error during API request: ${e.message}")
            return null
        }
    }

    private fun generateEventDescriptions(tokens: Int = 3500): Map<String, AIGeneratedDescription> {
        val contexts = findSourceCodeContextPerEvent()
        val eventDescriptions = mutableMapOf<String, AIGeneratedDescription>()
        for (event in metadata.events) {
            val context = contexts[event.name]
            if (context != null) {
                val prompt = createPrompt(event, context.getContextMaxTokens(tokens))
                println("Prompt for ${event.name}")
                println("========================================")
                println(prompt + "\n\n\n")
                val result = promptAI(prompt)
                result?.let { aiDescription ->
                    val description = AIGeneratedDescription(aiDescription,
                        context.sourceCodeContext.map {
                            it.relativeTo(openJDKFolder).toString()
                        })
                    println("Description for ${event.name}: ${description.description}")
                    eventDescriptions[event.name] = description
                }
            }
        }
        return eventDescriptions
    }

    /** runs into timeouts currently, maybe try it later */
    private fun normalizeTexts(descriptions: Map<String, String>): Map<String, String> {
        val prompt = { d: JSONObject ->
            """
        The following are descriptions JFR events in JSON format:
            $d
        Please normalize the descriptions so that they are all in the same format
        and improve their readability.
        """.trimIndent()
        }
        val keys = descriptions.keys.toMutableList()
        var currentObj = mutableMapOf<String, String>()
        val tokens = 3500
        val normalized = mutableMapOf<String, String>()
        val askAI = { p: String ->
            (promptAI(p)?.let { it ->
                normalized.putAll(JSONObject(it).toMap().mapValues { it.value.toString() })
            } ?: throw IOException("Empty response from AI"))
        }
        // normalize the descriptions in batches where each batch has at most 20000 tokens
        // any promptAI each batch separately
        while (keys.isNotEmpty()) {
            val key = keys.removeAt(0)
            currentObj[key] = descriptions[key]!!
            val currentPrompt = prompt(JSONObject(currentObj))
            if (currentPrompt.countTokens() > tokens) {
                currentObj.remove(key)
                val p = prompt(JSONObject(currentObj))
                println(p.countTokens())
                askAI(p)
                currentObj = mutableMapOf(key to descriptions[key]!!)
            }
        }
        if (currentObj.isNotEmpty()) {
            askAI(prompt(JSONObject(currentObj)))
        }
        return normalized
    }

    private fun normalizeDescriptions(eventDescriptions: Map<String, AIGeneratedDescription>): Map<String, AIGeneratedDescription> {
        val descriptions = eventDescriptions.map { it.key to it.value.description }
        val normalized = normalizeTexts(descriptions.toMap())
        return eventDescriptions.map { it.key to it.value.withDescription(normalized[it.key]!!) }.toMap()
    }

    fun process(tokens: Int = 3500, normalize: Boolean = false): me.bechberger.collector.xml.Metadata {
        var descriptions = generateEventDescriptions(tokens)
        if (normalize) {
            descriptions = normalizeDescriptions(descriptions)
        }
        for (event in metadata.events) {
            val description = descriptions[event.name.removePrefix("jdk.")]
            if (description != null) {
                event.aiGeneratedDescription = description
            }
        }
        return metadata
    }

    companion object {
        fun cppEventUsageRegexp(event: String) =
            Regex("(^|[^a-zA-Z_])Event${event.removePrefix("jdk.")}[&*]?[^a-zA-Z0-9_]+")

        fun javaEventUsageRegexp(event: String) =
            Regex("(^|[^a-zA-Z_.])${event.removePrefix("jdk.")}Event")

        class OpenAIConfig(val key: String, val baseURL: String)

        var openAIConfig: OpenAIConfig?

        private val OPENAI_CONFIG_FILE: Path = Path.of(".openai.key")

        private val prompt = """
            Explain the JFR event <event> concisely so that the reader,
            proficient in JFR, knows the meaning and relevance of the event to profiling and its fields,
            without giving code snippets or referencing the code directly,
            take the following code as the context of its usage and keep it short and structured
            (and in markdown format, so format it properly to make it readable, 
            using bullet points for field lists, but nothing else):
            <context>
            
            Now some information about the event:
            Fields:
            <fields>
            It is <flags>
            
            Don't mention implementation details, like methods, but explain the meaning of the event and how to use it for profiling.
            Keep field names lowercase and in backticks.
            Don't use headings.
            Don't repeat yourself.
        """.trimIndent()

        fun createPrompt(event: Event, context: String): String {
            val name = event.name.removePrefix("jdk.")
            val fields = event.fields.joinToString("\n") {
                "  - ${it.name} (type: ${it.type})" + (if (it.description != null) ": ${it.description}" else "") + " " + (if (it.label != "") ": ${it.label}" else "")
            }
            val flags = mapOf(
                "experimental" to event.experimental,
                "internal" to event.internal
            ).filter { it.value }.keys.joinToString(" and ")
            val miscFields = mapOf(
                "a stack trace" to event.stackTrace,
                "a thread" to event.thread,
                "a timestamp" to event.duration
            ).filter { it.value }.keys.joinToString(" and ")

            return prompt.replace("<event>", name)
                .replace("<fields>", fields)
                .replace("<flags>", flags)
                .replace("<context>", context)

        }

        init {
            if (!OPENAI_CONFIG_FILE.exists()) {
                OPENAI_CONFIG_FILE.writeText(
                    """
                       key=<your key>
                       server=https://api.openai.com
                   """.trimIndent()
                )
                openAIConfig = null
            } else {
                openAIConfig = OPENAI_CONFIG_FILE.readText().split("\n").map { it.split("=") }
                    .associate { it[0] to it[1] }.let {
                        OpenAIConfig(it["key"]!!, it["server"]!!)
                    }
            }
        }

        fun available() = openAIConfig != null
    }
}

fun main(args: Array<String>) {
    if (args.size != 3) {
        println(
            """  
            Usage: AIDescriptionAdder <path to metadata.xml> <path to OpenJDK source> <path to result xml file>
            
            The .openai.key file in the current directory must contain your OpenAI key and server url
            Format: 
              key=<your key>
              server=https://api.openai.com
        """.trimMargin()
        )
        return
    }
    val metadataPath = Paths.get(args[0])
    val sourcePath = Paths.get(args[1])
    val metadata = metadataPath.readXmlAs(me.bechberger.collector.xml.Metadata::class.java)
    val eventAdder = AIDescriptionAdder(sourcePath, metadata)
    val meta = eventAdder.process()
    val out = args[args.size - 1]
    if (out == "-") {
        println(meta)
    } else {
        Paths.get(out).writeText(meta.toString())
    }
}