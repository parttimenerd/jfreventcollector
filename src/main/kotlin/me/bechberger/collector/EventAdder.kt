package me.bechberger.collector

import me.bechberger.collector.xml.Configuration
import me.bechberger.collector.xml.Event
import me.bechberger.collector.xml.Field
import me.bechberger.collector.xml.SingleEventConfiguration
import me.bechberger.collector.xml.Transition
import me.bechberger.collector.xml.readXmlAs
import spoon.Launcher
import spoon.SpoonException
import spoon.reflect.declaration.CtAnnotation
import spoon.reflect.declaration.CtClass
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import me.bechberger.collector.xml.Metadata
import spoon.reflect.code.CtExpression
import spoon.reflect.code.CtLiteral
import spoon.reflect.code.CtNewArray
import kotlin.io.path.writeText
import kotlin.system.exitProcess

class AdderException(message: String) : RuntimeException(message)

/**
 * Add parsed events and info from found configurations
 *
 * @param openJDKFolder path to the OpenJDK source code
 * @param url url to the main folder of the OpenJDK source code on GitHUb
 * @param metadata metadata to extend with the found info
 */
class EventAdder(val openJDKFolder: Path, val metadata: me.bechberger.collector.xml.Metadata, val url: String) {

    fun configurations(): List<Configuration> {
        val configurations = mutableListOf<Configuration>()
        val configurationFiles =
            openJDKFolder.resolve("src/jdk.jfr").toFile().walkTopDown().filter { it.name.endsWith(".jfc") }
        configurationFiles.forEach {
            println("Reading configuration file $it")
            configurations.add(it.toPath().readXmlAs(Configuration::class.java))
        }
        return configurations
    }

    /** returns all possible event files, based only on the file name */
    fun maybeEventFiles(): List<File> {
        return openJDKFolder.resolve("src/jdk.jfr/share/classes/jdk/jfr").toFile().walkTopDown()
            .filter { it.name.endsWith("Event.java") }.toList()
    }

    internal fun parse(eventFile: File): CtClass<*>? {
        try {
            return Launcher.parseClass(eventFile.readText())
        } catch (e: SpoonException) {
            println("skipping ${eventFile.name} because it could not be parsed: ${e.message}")
        }
        return null
    }

    internal fun parseClass(sourcePath: Path, klass: CtClass<*>): Event {
        val event = Event()
        // add class level stuff
        event.name = ""
        event.category = ""
        klass.annotations.forEach { ann ->
            processClassAnnotation(ann, event, klass, ) { AdderException(it) }
        }
        event.fields.addAll(
            processFields(metadata, klass) { AdderException(it) }
        )
        event.source = openJDKFolder.relativize(sourcePath).toString()
        return event
    }

    companion object {
        fun processFields(metadata: Metadata, klass: CtClass<*>, exceptionCreator: (String) -> Any) =
            klass.fields.filter { it.isPublic && !it.isStatic && !it.isShadow }.map {
                val field = Field()
                field.name = it.simpleName
                field.type = it.type.simpleName
                val hasFrequency = it.annotations.any { ann -> ann.name == "Frequency" }
                it.annotations.forEach { ann ->
                    when (ann.name) {
                        "Label" -> field.label = ann.stringValue
                        "Timespan", "Timestamp", "DataAmount" -> {
                            if (!ann.values.contains("value")) {
                                field.contentType = when (ann.name) {
                                    "Timespan" -> "nanos"
                                    "Timestamp" -> "epochmillis"
                                    "DataAmount" -> "bytes"
                                    else -> throw AdderException("Unknown annotation ${ann.name}")
                                }
                            } else {
                                val contentType =
                                    metadata.findMatchingContentAnnotationOrAdd(ann.name, ann.value, hasFrequency)
                                field.contentType = contentType.name
                            }
                        }

                        "Description" -> field.description = ann.stringValue
                        "Experimental" -> field.experimental = true
                        "TransitionTo" -> field.transition = Transition.TO
                        "TransitionFrom" -> field.transition = Transition.FROM
                        "Unsigned" -> field.type = "u${field.type}"
                        "Frequency" -> {}
                        "CertificateId" -> {
                            assert(!hasFrequency)
                            field.contentType = metadata.findOrCreateContentType(
                                "certificateId",
                                ann.annotationType.qualifiedName,
                                "X509 Certificate Id"
                            ).name
                        }

                        "BooleanFlag" -> {}
                        "MemoryAddress" -> {}

                        else -> {
                            if (ann.annotationType.`package`.qualifiedName.startsWith("jdk.jfr")) {
                                throw exceptionCreator(
                                    "Unknown annotation ${ann.name} on field ${it.simpleName} " +
                                            "in class ${klass.qualifiedName}"
                                ) as Throwable
                            }
                        }
                    }
                }
                field
            }

        fun processClassAnnotation(
            ann: CtAnnotation<out Annotation>,
            event: Event,
            klass: CtClass<*>,
            allowGraalNames: Boolean = false,
            exceptionCreator: (String) -> Any
        ) {
            when (ann.name) {
                "Name" -> {
                    val stringValue = ann.stringValue
                    if ("." in stringValue && !stringValue.startsWith("jdk.") && (!allowGraalNames || !stringValue.startsWith(
                            "org.graalvm.compiler.truffle."
                        ))) {
                        throw AdderException("Event name $stringValue does not start with jdk.")
                    }
                    event.name = stringValue.substringAfter("jdk.")
                }

                "Label" -> event.label = ann.stringValue
                "Description" -> event.description = ann.stringValue
                "Category" -> event.category = ann.stringArray.joinToString(", ")
                "Experimental" -> event.experimental = true
                "Thread" -> event.thread = ann.booleanValue
                "StackTrace" -> event.stackTrace = ann.booleanValue
                "Enabled" -> event.enabled = ann.booleanValue
                "Internal" -> event.internal = ann.booleanValue
                "Throttle" -> event.throttle = ann.booleanValue
                "Cutoff" -> event.cutoff = ann.booleanValue
                "Registered", "MirrorEvent" -> {}
                "Period" -> event.period = ann.stringValue
                "RemoveFields" -> {
                    // annotation that can remove the fields "duration", "eventThread" and "startTime"
                    val removeFields = ann.stringArray
                    for (field in event.fields.toList()) {
                        if (field.name in removeFields) {
                            event.fields.remove(field)
                        }
                    }
                    for (removed in removeFields) {
                        when (removed) {
                            "duration" -> event.duration = false
                            "eventThread" -> event.thread = false
                            "startTime" -> event.startTime = false
                            "stackTrace" -> event.stackTrace = false
                            else -> throw exceptionCreator("Unknown field $removed in RemoveFields annotation") as Throwable
                        }
                    }
                }
                "StackFilter" -> {
                    event.stackFilter = ann.stringArray
                }

                else -> {
                    if (ann.annotationType.`package`.qualifiedName.startsWith("jdk.jfr")) {
                        throw exceptionCreator("Unknown annotation ${ann.name} on class ${klass.qualifiedName}") as Throwable
                    }
                }
            }
        }
    }

    private fun findEventDescendants(nodes: Map<String, ClassHierarchyNode>): Set<ClassHierarchyNode> {
        val hierarchyNodes = nodes.values
        val eventDescendants = mutableSetOf(nodes["jdk.jfr.Event"]!!)
        val remainingNodes = hierarchyNodes.toMutableSet()
        remainingNodes.removeAll(eventDescendants)
        var somethingChanged = true
        while (somethingChanged) {
            somethingChanged = false
            for (node in remainingNodes.toList()) {
                if (eventDescendants.contains(node.parent)) {
                    eventDescendants.add(node)
                    remainingNodes.remove(node)
                    somethingChanged = true
                }
            }
        }
        for (node in remainingNodes) {
            println("skipping ${node.name} because it is not a descendant of jdk.jfr.Event")
        }
        return eventDescendants
    }

    /** returns the nodes of the lowest layer which are descendants of jdk.jfr.Event and are not abstract */
    private fun createHierarchy(classes: List<Pair<Path, CtClass<*>>>): List<ClassHierarchyNode> {
        val nodes = classes.associate { (path, klass) ->
            klass.qualifiedName to
                ClassHierarchyNode(
                    klass.qualifiedName,
                    event = parseClass(path, klass),
                    parentName = klass.superclass?.qualifiedName?.let { name ->
                        if (name.startsWith("jdk.jfr")) name else "${klass.`package`.qualifiedName}.$name"
                    },
                    realClass = !klass.isAbstract
                )
        }
        nodes.values.forEach { node ->
            node.parentName?.let { parentName ->
                nodes[parentName]?.let { parent ->
                    node.parent = parent
                }
            }
        }
        val eventDescendants = findEventDescendants(nodes)
        val rootNodes = eventDescendants.filter { it.parent != null && it.realClass }
        return rootNodes
    }

    private fun addConfigurations(meta: me.bechberger.collector.xml.Metadata, configurations: List<Configuration>) {
        meta.configurations.addAll(configurations)
        meta.events.forEach { event ->
            event.configurations.addAll(
                configurations.mapIndexedNotNull { index, configuration ->
                    configuration.get(event.name)?.let {
                        SingleEventConfiguration(index, it.settings)
                    }
                }
            )
        }
    }

    fun process(): me.bechberger.collector.xml.Metadata {
        val eventNodes = createHierarchy(
            maybeEventFiles().map { it.toPath() to parse(it) }.filter { it.second != null }
                .map { it.first to it.second!! }
        )
        val meta = metadata.copy().also { it.events.addAll(eventNodes.map { event -> event.mergedEvent }) }
        meta.url = url
        addConfigurations(meta, configurations())
        return meta
    }
}

fun main(args: Array<String>) {
    if (args.size != 4) {
        println("Usage: EventAdder <path to metadata.xml> <path to OpenJDK source> " +
                "<url to main folder> <path to result xml file>")
        exitProcess(1)
    }
    val metadataPath = Paths.get(args[0])
    val sourcePath = Paths.get(args[1])
    val url = args[2]
    val metadata = metadataPath.readXmlAs(me.bechberger.collector.xml.Metadata::class.java)
    val eventAdder = EventAdder(sourcePath, metadata, url)
    val meta = eventAdder.process()
    val out = args[args.size - 1]
    if (out == "-") {
        println(meta)
    } else {
        Paths.get(out).writeText(meta.toString())
    }
}
