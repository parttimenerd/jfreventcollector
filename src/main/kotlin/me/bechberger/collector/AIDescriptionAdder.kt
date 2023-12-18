package me.bechberger.collector

import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import me.bechberger.collector.xml.readXmlAs
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Finds the usage context of every event in the JDK source code and adds a generated description
 * based on this context */
class AIDescriptionAdder(val openJDKFolder: Path, val metadata: me.bechberger.collector.xml.Metadata) {

    private fun getEventCPPNames(): List<String> {
       return metadata.events.map { "Event${it.name.removePrefix("jdk.")}" }
    }

    data class ContextLines(val file: Path, val lineNumbers: List<Int>, val content: String)

    class EventUsageContext(val event: String, val sourceCodeContext: List<Path>) {
        fun getLinesOfContext(n: Int): List<ContextLines> {
            val re = eventUsageRegexp(event)
            val linesOfContext = mutableListOf<ContextLines>()
            sourceCodeContext.forEach { file ->
                val allowedLines = n / sourceCodeContext.size
                val lines = file.readText().split("\n")
                val eventLines = lines.mapIndexed { index, s -> if (re.containsMatchIn(s)) index else -1 }.filter { it != -1 }

                assert(eventLines.size >= 1) { "No usage of $event found in $file" }

                val diff = eventLines.last() - eventLines.first()
                assert(diff > allowedLines * 0.8) { "Not enough lines of context for $event in $file" }

                val middle = (eventLines.first() + eventLines.last()) / 2

                val start = maxOf(0, middle - allowedLines / 2)
                val end = minOf(lines.size, middle + allowedLines / 2)
                if (end < allowedLines) {
                    // add more lines from the beginning if there are not enough lines at the end
                    val missingLines = allowedLines - end
                    val start = maxOf(0, start - missingLines)
                }
                if (start > lines.size - allowedLines) {
                    // add more lines from the end if there are not enough lines at the beginning
                    val missingLines = allowedLines - (lines.size - start)
                    val end = minOf(lines.size, end + missingLines)
                }
                linesOfContext.add(ContextLines(file, eventLines, lines.subList(start, end).joinToString { "\n" }))
            }
            return linesOfContext
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun findSourceCodeContextPerEvent(): Map<String, EventUsageContext> {
        val eventNames = metadata.events.map { it.name }
        val contexts = mutableMapOf<String, MutableList<Path>>()
        val res = eventNames.associate { it to eventUsageRegexp(it) }
        openJDKFolder.resolve("src").walk().filter { it.extension in listOf("h", "cpp", "c", "hpp") }.forEach { file ->
            // look for the "<event name> <identifier>;" pattern for every event
            eventNames.forEach { event->
                if (res[event]!!.matches(file.readText())) {
                    contexts.computeIfAbsent(event) { mutableListOf() }.add(file)
                }
            }
        }
        return contexts.map { (event, context) -> EventUsageContext(event, context) }.associateBy { it.event }
    }

    private fun promptGPT3(prompt: String, apiKey: String, baseUrl: String): String {
        val url = "$baseUrl/openai/deployments/gpt-35-4k-0613/chat/completions?api-version=2023-05-15"
        val requestBody = """
        {"messages": [{"role": "user", "content": ${JSONObject.quote(prompt)}}]}
    """.trimIndent().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("api-key", "$apiKey")
            .post(requestBody)
            .build()

        val client = OkHttpClient()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response")
        }

        return response.body?.string() ?: throw IOException("Empty response body")
    }

    fun promptAI(prompt: String) {
        try {
            val response = promptGPT3(prompt, openAIConfig!!.key, openAIConfig!!.baseURL)
            println("Generated response:\n$response")
        } catch (e: IOException) {
            println("Error during API request: ${e.message}")
        }
    }


    fun process(): me.bechberger.collector.xml.Metadata {
        promptAI("Hi, I'm a bot!")
        System.exit(0)
        val contexts = findSourceCodeContextPerEvent()
        for (event in metadata.events) {
            val context = contexts[event.name]
            if (context != null) {
                println("Context for ${event.name} found: ${context.getLinesOfContext(1000).size}")
            }
        }
        System.exit(0)
        return metadata
    }

    companion object {
        fun eventUsageRegexp(event: String) = Regex("[^a-zA-Z_]Event${event.removePrefix("jdk.")} [a-zA-Z0-9_]+;")

        class OpenAIConfig(val key: String, val baseURL: String)
        var openAIConfig: OpenAIConfig?

        val OPENAI_CONFIG_FILE = Path.of(".openai.key")

        val prompt = """
            Explain the JFR event <event> concisely so that the reader,
            proficient in JFR, knows the meaning and relevance of the event and its properties,
            without giving code snippets or referencing the code directly,
            take the following code as the context of its usage and keep it short and structured:
            <context>
        """.trimIndent()

        fun createPrompt(event: String, context: String) = prompt.replace("<event>", event).replace("<context>", context)

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
                openAIConfig = OPENAI_CONFIG_FILE.readText().split("\n").map { it.split("=") }.map { it[0] to it[1] }.toMap().let {
                    OpenAIConfig(it["key"]!!, it["server"]!!)
                }
            }
        }

        fun available() = openAIConfig != null
    }
}

fun main(args: Array<String>) {
    if (args.size != 3) {
        println("""  
            Usage: AIDescriptionAdder <path to metadata.xml> <path to OpenJDK source> <path to result xml file>
            
            The .openai.key file in the current directory must contain your OpenAI key and server url
            Format: 
              key=<your key>
              server=https://api.openai.com
        """.trimMargin())
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