package me.bechberger.collector

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import me.bechberger.collector.xml.Event
import me.bechberger.collector.xml.Metadata
import me.bechberger.collector.xml.readXmlAs
import spoon.Launcher
import spoon.SpoonException
import spoon.reflect.code.CtInvocation
import spoon.reflect.code.CtLiteral
import spoon.reflect.declaration.CtClass
import kotlin.io.path.writeText
import kotlin.system.exitProcess

class GraalAdderException(message: String) : RuntimeException(message)

/**
 * Add parsed events and info from found configurations
 *
 * @param openJDKFolder path to the OpenJDK source code
 * @param url url to the main folder of the OpenJDK source code on GitHUb
 * @param metadata metadata to extend with the found info
 */
class GraalEventAdder(
    private val openJDKFolder: Path, val metadata: Metadata, val url: String, private val graalVersion: String, private val graalTag: String
) {

    /** find "jfr" folders */
    private fun findJFRFolders(): List<Path> {
        return openJDKFolder.toFile().walkTopDown().filter { it.name == "jfr" && "/test/" !in it.toString() }
            .map { it.toPath() }.toList()
    }

    private val jfrFolders = findJFRFolders()

    /**
     * Returns all possible event files, current heuristic:
     * <ul>
     *     <li>file name contains "Event"</li>
     *     <li>file contains "import jdk.jfr"</li>
     * </ul>
     */
    private fun maybeEventFiles(): List<File> {
        return jfrFolders.flatMap { f ->
            f.toFile().walkTopDown().filter { it.name.contains("Event") && it.readText().contains("import jdk.jfr") }
                .toList()
        }
    }

    private fun findJfrEventClass(): Path {
        val files = jfrFolders.flatMap { f ->
            f.toFile().walkTopDown().filter { it.name == "JfrEvent.java" }.map { it.toPath() }
        }
        if (files.isEmpty()) {
            throw GraalAdderException("No JfrEvent.java found in jfr folders")
        }
        if (files.size > 1) {
            throw GraalAdderException("More than one JfrEvent.java found in jfr folders")
        }
        return files.first()
    }

    private fun parseJfrEventClass(): CtClass<*> {
        val jfrEventClass = findJfrEventClass()
        return Launcher.parseClass(jfrEventClass.toFile().readText())
    }

    /** returns the number of discovered events */
    private fun processJfrEventClass(): Int {
        val jfrEventClass = parseJfrEventClass()
        val events =
            jfrEventClass.fields.filter { it.type.simpleName == "JfrEvent" }.map { it.defaultExpression }.map {
                    if (it !is CtInvocation<*> || it.executable.simpleName != "create") {
                        throw GraalAdderException("Unexpected event creation $it")
                    }
                    it.arguments.first()
                }.map { (it as CtLiteral<*>).value as String }
        events.forEach { event ->
            metadata.getEvent(event)?.includedInGraal() ?: println("event $event not found in metadata")
        }
        return events.size
    }

    internal fun parse(eventFile: File): CtClass<*>? {
        try {
            return Launcher.parseClass(eventFile.readText())
        } catch (e: SpoonException) {
            println("skipping ${eventFile.name} because it could not be parsed: ${e.message}")
        }
        return null
    }

    private fun parseClass(sourcePath: Path, klass: CtClass<*>): Event {
        val event = Event()
        // add class level stuff
        event.name = ""
        event.category = ""
        event.includedInVariant = Event.GRAAL_ONLY
        klass.annotations.forEach { ann ->
            EventAdder.processClassAnnotation(ann, event, klass, true) { GraalAdderException(it) }
        }
        event.fields.addAll(EventAdder.processFields(metadata, klass) { GraalAdderException(it) })
        event.graalSource = openJDKFolder.relativize(sourcePath).toString()
        return event
    }

    private fun findEventDescendants(nodes: Map<String, ClassHierarchyNode>): Set<ClassHierarchyNode> {
        val hierarchyNodes = nodes.values
        val eventDescendants: MutableMap<String, ClassHierarchyNode> =
            nodes.filter { it.value.parentName == "jdk.jfr.Event" }.toMutableMap()

        val remainingNodes = hierarchyNodes.filter { it.parentName != null }.toMutableSet()
        remainingNodes.removeAll(eventDescendants.values.toSet())
        var somethingChanged = true
        while (somethingChanged) {
            somethingChanged = false
            for (node in remainingNodes.toList()) {
                if (eventDescendants.contains(node.parentName)) {
                    eventDescendants[node.name] = node
                    remainingNodes.remove(node)
                    somethingChanged = true
                }
            }
        }
        for (node in remainingNodes) {
            if (node.name.endsWith("Substitution")) {
                continue
            }
            println("skipping ${node.name} because it is not a descendant of jdk.jfr.Event")
        }
        return eventDescendants.values.toSet()
    }

    /** returns the nodes of the lowest layer which are descendants of jdk.jfr.Event and are not abstract */
    private fun createHierarchy(classes: List<Pair<Path, CtClass<*>>>): List<ClassHierarchyNode> {
        val nodes = classes.associate { (path, klass) ->
            klass.qualifiedName to ClassHierarchyNode(
                klass.qualifiedName,
                event = parseClass(path, klass),
                parentName = klass.superclass?.qualifiedName?.let { name ->
                    if (name.startsWith("jdk.jfr") || name.startsWith("org.")) name else "${klass.`package`.qualifiedName}.$name"
                },
                realClass = !klass.isAbstract
            )
        }
        nodes.values.forEach { node ->
            node.parentName?.let { parentName ->
                nodes[parentName]?.let { parent ->
                    node.parent = parent
                    node.parent?.descendants?.add(node)
                }
            }
        }
        val eventDescendants = findEventDescendants(nodes)
        val rootNodes = eventDescendants.filter {
            it.descendants.isEmpty() && it.realClass && it.name !in setOf(
                "EveryChunkNativePeriodicEvents",
                "EndChunkNativePeriodicEvents"
            )
        }
        return rootNodes
    }

    fun process(): Metadata {
        val eventNodes = createHierarchy(maybeEventFiles().map { it.toPath() to parse(it) }.filter { it.second != null }
            .map { it.first to it.second!! })
        // print every event node with information
        metadata.events.addAll(eventNodes.map { it.mergedEvent })
        val oldEventCount = processJfrEventClass()
        val newEventCount = eventNodes.size
        println("Found $oldEventCount existing events and $newEventCount new events")
        metadata.graalVMInfo = Metadata.GraalVMInfo(graalVersion, url, graalTag)
        return metadata
    }
}

fun main(args: Array<String>) {
    if (args.size != 6) {
        println("Usage: GraalEventAdder <path to metadata.xml> <path to graal source> <url to main folder> <graal version> <graal tag> <path to result xml file>")
        exitProcess(1)
    }
    val metadataPath = Paths.get(args[0])
    val sourcePath = Paths.get(args[1])
    val url = args[2]
    val graalVersion = args[3]
    val graalTag = args[4]
    val metadata = metadataPath.readXmlAs(Metadata::class.java)
    val eventAdder = GraalEventAdder(sourcePath, metadata, url, graalVersion, graalTag)
    val meta = eventAdder.process()
    val out = args[args.size - 1]
    if (out == "-") {
        println(meta)
    } else {
        Paths.get(out).writeText(meta.toString())
    }
}
