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
import kotlin.io.path.writeText

class AdderException(message: String) : RuntimeException(message)

data class ClassHierarchyNode(
    val name: String,
    val event: Event,
    val parentName: String?,
    val realClass: Boolean,
    var parent: ClassHierarchyNode? = null
) {
    val mergedEvent: Event
        get() {
            return parent?.mergedEvent?.let {
                it.merge(event)
            } ?: event
        }
}

val CtAnnotation<*>.stringValue: String
    get() {
        try {
            return this.getValueAsString("value")
        } catch (e: Exception) {
            /** Handle @Name(Type.EVENT_NAME_PREFIX + "ActiveSetting") */
            val value = this.values.get("value")!!.toString().replace(Regex("[\" +]"), "")
            if (value.contains("Type.EVENT_NAME_PREFIX")) {
                return value.replace("Type.EVENT_NAME_PREFIX", "")
            }
            throw AdderException("Unknown annotation value: $value for annotation $this")
        }
    }

val CtAnnotation<*>.stringArray: List<String>
    get() = (this.getValueAsObject("value")!!.also { assert(it is Array<*>) } as Array<String>).toList()

val CtAnnotation<*>.booleanValue: Boolean
    get() = this.values["value"]!!.toString().toBooleanStrict()

val CtAnnotation<*>.value
    get() = this.values["value"]?.toString() ?: throw AdderException("No value for annotation $this")

/**
 * Add parsed events and info from found configurations
 *
 * @param metadata metadata to extend with the found info
 */
class EventAdder(val openJDKFolder: Path, val metadata: me.bechberger.collector.xml.Metadata) {

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
            when (ann.name) {
                "Name" -> event.name = ann.stringValue
                "Label" -> event.label = ann.stringValue
                "Description" -> event.description = ann.stringValue
                "Category" -> event.category = ann.stringArray.joinToString(", ")
                "StartTime" -> event.startTime = ann.booleanValue
                "Experimental" -> event.experimental = true
                "Thread" -> event.thread = ann.booleanValue
                "StackTrace" -> event.stackTrace = ann.booleanValue
                "Enabled" -> event.enabled = ann.booleanValue
                "Internal" -> event.internal = ann.booleanValue
                "Throttle" -> event.throttle = ann.booleanValue
                "Cutoff" -> event.cutoff = ann.booleanValue
                "Registered", "MirrorEvent" -> {}
                else -> {
                    println("Unknown annotation ${ann.name} on class ${klass.qualifiedName}")
                }
            }
        }
        event.fields.addAll(
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
                        else -> {
                            if (ann.annotationType.`package`.qualifiedName.startsWith("jdk.jfr")) {
                                println(
                                    "Unknown annotation ${ann.name} on field ${it.simpleName} " +
                                        "in class ${klass.qualifiedName}"
                                )
                            }
                        }
                    }
                }
                field
            }
        )
        event.source = openJDKFolder.relativize(sourcePath).toString()
        return event
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
        addConfigurations(meta, configurations())
        return meta
    }
}

fun main(args: Array<String>) {
    if (args.size != 3) {
        println("Usage: EventAdder <path to metadata.xml> <path to OpenJDK source> <path to result xml file>")
        return
    }
    val metadataPath = Paths.get(args[0])
    val sourcePath = Paths.get(args[1])
    val metadata = metadataPath.readXmlAs(me.bechberger.collector.xml.Metadata::class.java)
    val eventAdder = EventAdder(sourcePath, metadata)
    val meta = eventAdder.process()
    val out = args[args.size - 1]
    if (out == "-") {
        println(meta)
    } else {
        Paths.get(out).writeText(meta.toString())
    }
}
