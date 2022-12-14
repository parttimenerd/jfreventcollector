package me.bechberger.collector

import java.nio.file.Path
import java.nio.file.Paths
import java.text.DecimalFormat
import jdk.jfr.EventType
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedObject
import jdk.jfr.consumer.RecordingFile

/**
 * Collect information for all events in a recording file
 */
class Processor(val file: Path) {

    val events = mutableMapOf<EventType, MutableList<RecordedEvent>>()
    val eventCounts = mutableMapOf<String, Int>()
    var eventSum: Int = 0
    val typeNames = mutableMapOf<String, Any>()

    private fun getEventCount(event: RecordedEvent) = eventCounts.getOrDefault(event.eventType.name, 0)

    fun processField(obj: RecordedObject, field: String, fieldType: String) {
        val value = obj.getValue<Any>(field) ?: return
        if (!typeNames.contains(fieldType)) {
            typeNames[fieldType] = value
        }
        if (value is RecordedObject) {
            for (f in value.fields) {
                processField(value, f.name, f.typeName)
            }
        }
    }

    fun processEvent(event: RecordedEvent) {
        events.computeIfAbsent(event.eventType) { mutableListOf() }.add(event)
        event.fields.forEach { field ->
            processField(event, field.name, field.typeName)
        }
    }

    fun collect() {
        RecordingFile(file).use { recording ->
            while (recording.hasMoreEvents()) {
                val event = recording.readEvent()
                if (getEventCount(event) < MAX_EVENTS_PER_TYPE) {
                    processEvent(event)
                }
                eventCounts[event.eventType.name] = eventCounts.getOrDefault(event.eventType.name, 0) + 1
                eventSum++
            }
        }
    }

    fun process(): Processor {
        collect()
        return this
    }

    fun printType(obj: RecordedEvent) {
        val type = obj.eventType
        println("  Label: ${type.label}")
        println("  Description: ${type.description}")
        println("  Category: ${type.categoryNames}")
        println("  Enabled: ${type.isEnabled}")
        println("  Settings:")
        fun printlnn(label: String, value: Any?) = value?.let { println("      $label: $value") }
        for (setting in type.settingDescriptors) {
            println("    ${setting.name}: ${setting.label ?: ""}")
            printlnn("Description", setting.description)
            printlnn("Type", setting.typeName)
            printlnn("Content Type", setting.contentType)
            printlnn("Default", setting.defaultValue)
        }
        println("  Fields:")
        printObject(obj, "    ")
    }

    fun printObject(obj: RecordedObject, indent: String = "  ") {
        fun printlnn(label: String, value: Any?) = value?.let { println("$indent  $label: $value") }
        obj.fields.forEach { field ->
            val value = obj.getValue<Any>(field.name)
            println("${indent}${field.name}: ${field.label ?: ""} ${if (field.isArray) "[]" else ""}")
            printlnn("Type", field.typeName)
            printlnn("Description", field.description)
            printlnn("Content Type", field.contentType)
            if (value is RecordedObject) {
                printObject(value, "$indent  ")
            } else {
                printlnn("Example Value", value)
            }
        }
    }

    fun printSummaryStats() {
        println("Summary:")
        println("  Events: $eventSum")
        println("  Event Types: ${events.size}")
        for (type in events.keys) {
            println(
                "    %40s | %10d | %8s"
                    .format(type.name, eventCounts[type.name], percentage(eventCounts[type.name]!!))
            )
        }
    }

    fun percentage(value: Int) = DecimalFormat("##.000%").format(value.toDouble() / (eventSum * 1.0))

    fun print() {
        printSummaryStats()
        println()
        events.forEach { (type, e) ->
            println("${type.name} (${eventCounts[type.name]} / ${percentage(eventCounts[type.name]!!)} of $eventSum)")
            val event = e.first()
            printType(event)
            println("Example:")
            println(event.toString().ident("  "))
            println()
        }
        println("\n\nTypes\n=========================")
        typeNames.forEach { (type, value) ->
            if (value is RecordedObject) {
                println("$type:")
                printObject(value)
                println("Example:")
                println(value.toString().ident("  "))
            } else {
                println(type)
                println("Java Type: ${value.javaClass.name}")
                println("Example: $value")
            }
            println()
        }
    }

    companion object {
        const val MAX_EVENTS_PER_TYPE = 100
    }
}

fun String.ident(ident: String) = this.split("\n").joinToString("\n") { "$ident$it" }

fun main(args: Array<String>) {
    Processor(Paths.get(args[0])).process().print()
}
