package me.bechberger.collector

import me.bechberger.collector.xml.Event
import spoon.reflect.code.CtExpression
import spoon.reflect.code.CtLiteral
import spoon.reflect.code.CtNewArray
import spoon.reflect.declaration.CtAnnotation

class ClassHierarchyNode(
    val name: String,
    val event: Event,
    val parentName: String?,
    val realClass: Boolean,
    var parent: ClassHierarchyNode? = null,
    var descendants: MutableSet<ClassHierarchyNode> = mutableSetOf()
) {
    val mergedEvent: Event
        get() {
            return parent?.mergedEvent?.merge(event) ?: event
        }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return other is ClassHierarchyNode && other.name == name
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
    get() = when (val value = getValue<CtExpression<*>>("value")) {
    is CtLiteral<*> -> listOf(value.value.toString())
    is CtNewArray<*> -> value.elements.map { (it as CtLiteral<*>).value.toString() }
    else -> throw AssertionError("Unknown value $value in RemoveFields annotation")
}

val CtAnnotation<*>.booleanValue: Boolean
    get() = this.values["value"]!!.toString().toBooleanStrict()
val CtAnnotation<*>.value
    get() = this.values["value"]?.toString() ?: throw AdderException("No value for annotation $this")