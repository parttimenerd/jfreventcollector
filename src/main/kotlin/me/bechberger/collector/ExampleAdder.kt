package me.bechberger.collector

import jdk.jfr.consumer.RecordedObject
import me.bechberger.collector.xml.AbstractType
import me.bechberger.collector.xml.Event
import me.bechberger.collector.xml.EventExample
import me.bechberger.collector.xml.Example
import me.bechberger.collector.xml.FieldType
import me.bechberger.collector.xml.Type
import me.bechberger.collector.xml.readXmlAs
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.writeText

/** Adds examples to the events and types */
class ExampleAdder(val metadata: me.bechberger.collector.xml.Metadata) {

    fun addEventsFromFile(label: String, description: String, file: Path) {
        assert(metadata.exampleFiles.none { it.label == label || it.description == description })
        val processor = Processor(file)
        processor.process()
        val id = metadata.exampleFiles.size
        metadata.exampleFiles.add(me.bechberger.collector.xml.ExampleFile(label, description))
        addEventExamples(id, processor)
    }

    private fun addEventExamples(id: Int, processor: Processor) {
        val events = metadata.events.map { "jdk." + it.name to it }.toMap()
        for ((type, event) in processor.events) {
            if (type.name in events) {
                val metadataEvent = events[type.name]!!
                addToEvent(id, metadataEvent, event)
                metadataEvent.appearedIn.add(id)
            }
        }
    }

    private fun addToEvent(id: Int, event: Event, obj: RecordedObject): EventExample {
        return addToType(id, event, obj, EventExample(id))
    }

    private fun addToAbstractType(id: Int, type: AbstractType<Example>?, value: Any?): Example {
        type?.let {
            type.appearedIn.add(id)
        }
        return when (value) {
            is RecordedObject -> {
                when (type) {
                    null -> addToType(id, type, value)
                    is Type -> addToType(id, type, value)
                    else -> addToType(id, null, value).also {
                        if (type.examples.size < MAX_EXAMPLES) {
                            type.examples.add(it)
                        }
                    }
                }
            }
            else -> {
                Example(id, FieldType.STRING).also {
                    val string = value.toString()
                    it.stringValue = if (string.length < MAX_TEXT_LENGTH) {
                        value.toString()
                    } else {
                        it.isTruncated = true
                        value.toString().substring(0, MAX_TEXT_LENGTH)
                    }
                    type?.examples?.let { examples ->
                        if (examples.size < MAX_EXAMPLES) {
                            examples.add(it)
                        }
                    }
                }
            }
        }
    }

    private fun addToType(id: Int, type: Type<Example>?, obj: RecordedObject): Example {
        return addToType(id, type, obj, Example(id, FieldType.OBJECT))
    }

    private fun <T : Example> addToType(
        id: Int,
        type: Type<T>?,
        obj: RecordedObject?,
        example: T
    ): T {
        if (obj == null) {
            example.type = FieldType.NULL
            return example
        }
        val map = mutableMapOf<String, Example>()
        val fieldNames = (
            type?.fields?.associate { it.name to metadata.getType(it.contentType, it.type) }
                ?: obj.fields.associate { it.name to null }
            ).toMutableMap()
        if (type != null && type is Event) {
            if (type.stackTrace) {
                fieldNames["stackTrace"] = metadata.getType("StackTrace")
            }
            if (type.thread) {
                fieldNames["thread"] = metadata.getType("Thread")
            }
            if (type.startTime) {
                fieldNames["startTime"] = metadata.getType("millis")
            }
        }
        for ((field, fieldType) in fieldNames) {
            if (!obj.hasField(field)) {
                continue
            }
            when (val fieldValue = obj.getValue<Any>(field)) {
                null -> {
                    map[field] = addToAbstractType(id, fieldType, null)
                }
                is Array<*> -> {
                    val array = obj.getValue<Array<*>>(field)
                    map[field] = Example(id, FieldType.ARRAY).also {
                        if (array.size > MAX_ARRAY_LENGTH) {
                            it.isTruncated = true
                        }
                        it.arrayValue =
                            array.take(if (field != "frames") MAX_ARRAY_LENGTH else 1)
                                .map { elem -> addToAbstractType(id, fieldType, elem) }
                                .toMutableList()
                    }
                }
                else -> {
                    map[field] = addToAbstractType(id, fieldType, fieldValue)
                }
            }
        }
        return example.also {
            it.objectValue = map; it.type = FieldType.OBJECT; type?.examples?.let { examples ->
                if (examples.size < MAX_EXAMPLES) {
                    examples.add(it)
                }
            }
        }
    }

    companion object {
        const val MAX_EXAMPLES = 3
        const val MAX_ARRAY_LENGTH = 2
        const val MAX_TEXT_LENGTH = 300
    }
}

fun main(args: Array<String>) {
    if (args.size < 4 || (args.size - 2) % 3 != 0) {
        println(
            "Usage: ExampleAdder <path to metadata.xml> <label of file> <description of file> <JFR file> ... " +
                "<path to resulting metadata.xml>"
        )
        return
    }
    val metadataPath = Paths.get(args[0])
    val metadata = metadataPath.readXmlAs(me.bechberger.collector.xml.Metadata::class.java)
    println("Read metadata from $metadataPath: ${metadata.events.size} events, ${metadata.types.size} types")
    val eventAdder = ExampleAdder(metadata)
    for (i in 1 until args.size step 3) {
        if (args.size - i < 3) {
            break
        }
        val label = args[i]
        val description = args[i + 1]
        val file = Paths.get(args[i + 2])
        eventAdder.addEventsFromFile(label, description, file)
    }
    val out = args[args.size - 1]
    if (out == "-") {
        println(metadata)
    } else {
        Paths.get(out).writeText(metadata.toString())
    }
}
