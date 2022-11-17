package me.bechberger.collector.xml

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement

@JacksonXmlRootElement
class Metadata {

    @JacksonXmlProperty(localName = "Event")
    lateinit var events: MutableList<Event>

    @JacksonXmlProperty(localName = "Type")
    lateinit var types: List<Type>

    @JacksonXmlProperty(localName = "Relation")
    lateinit var relations: List<Relation>

    @JacksonXmlProperty(localName = "XmlType")
    lateinit var xmlTypes: List<XmlType>

    @JacksonXmlProperty(localName = "XmlContentType")
    lateinit var xmlContentTypes: MutableList<XmlContentType>

    @JacksonXmlProperty(localName = "configuration")
    var configurations: MutableList<Configuration> = ArrayList()

    fun copy(): Metadata {
        val meta = Metadata()
        meta.events = events.toMutableList()
        meta.types = types.toList()
        meta.relations = relations.toList()
        meta.xmlTypes = xmlTypes.toList()
        meta.xmlContentTypes = xmlContentTypes.toMutableList()
        meta.configurations = configurations.toMutableList()
        return meta
    }

    fun findMatchingContentAnnotationOrAdd(
        annotationType: String,
        valueExpression: String,
        hasFrequency: Boolean = false
    ): XmlContentType {
        return xmlContentTypes.find { it.matchesAnnotation(annotationType, valueExpression) } ?: XmlContentType.create(
            annotationType,
            valueExpression,
            hasFrequency
        ).also { xmlContentTypes.add(it) }
    }

    fun findOrCreateContentType(name: String, annotation: String, label: String): XmlContentType {
        return xmlContentTypes.find { it.name == name } ?: XmlContentType.create(name, annotation, label)
            .also { xmlContentTypes.add(it) }
    }

    fun findMatchingType(fullyQualifiedJavaType: String): XmlType? {
        return xmlTypes.find { it.matchesJavaType(fullyQualifiedJavaType) }
    }

    override fun toString() = objectToXml(this)
}

class Event {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    /** Category, subcategory, subsubcategory */
    @JacksonXmlProperty(isAttribute = true)
    lateinit var category: String

    @JacksonXmlProperty(isAttribute = true)
    lateinit var label: String

    @JacksonXmlProperty(isAttribute = true)
    var description: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var startTime: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var experimental: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var thread: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var stackTrace: Boolean = false

    /** Internal can be ignored */
    @JacksonXmlProperty(isAttribute = true)
    var internal: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var throttle: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var cutoff: Boolean = false

    /** just for events from code, all other events are enabled by default */
    @JacksonXmlProperty(isAttribute = true)
    var enabled: Boolean = true

    @JacksonXmlProperty(isAttribute = true)
    var period: Period? = null

    @JacksonXmlProperty(localName = "Field")
    var fields: MutableList<Field> = ArrayList()

    @JacksonXmlProperty(localName = "Configuration")
    var configurations: MutableList<SingleEventConfiguration> = ArrayList()

    @JacksonXmlProperty(isAttribute = true)
    var source: String? = null

    fun categories(): List<String> = category.split(", ")

    constructor(
        name: String = "",
        category: String = "",
        label: String = "",
        description: String? = null,
        startTime: Boolean = false,
        experimental: Boolean = false,
        thread: Boolean = false,
        stackTrace: Boolean = false,
        internal: Boolean = false,
        throttle: Boolean = false,
        cutoff: Boolean = false,
        enabled: Boolean = true,
        period: Period? = null,
        fields: MutableList<Field> = ArrayList(),
        configurations: MutableList<SingleEventConfiguration> = ArrayList(),
        source: String? = null
    ) {
        this.name = name
        this.category = category
        this.label = label
        this.description = description
        this.startTime = startTime
        this.experimental = experimental
        this.thread = thread
        this.stackTrace = stackTrace
        this.internal = internal
        this.throttle = throttle
        this.cutoff = cutoff
        this.enabled = enabled
        this.period = period
        this.fields = fields
        this.configurations = configurations
        this.source = source
    }

    fun merge(other: Event): Event {
        val newEvent = Event()

        fun merge(first: String, second: String): String {
            return if (second.isNotEmpty()) second else first
        }

        fun merge(first: String?, second: String?): String? {
            return if (second?.isNotEmpty() == true) second else first
        }

        return Event(
            merge(name, other.name),
            merge(category, other.category),
            merge(label, other.label),
            merge(description, other.description),
            startTime || other.startTime,
            experimental || other.experimental,
            thread || other.thread,
            stackTrace || other.stackTrace,
            internal || other.internal,
            throttle || other.throttle,
            cutoff || other.cutoff,
            enabled && other.enabled,
            period ?: other.period,
            (fields + other.fields).toMutableList(),
            (configurations + other.configurations).toMutableList(),
            merge(source, other.source)
        )
    }

    override fun toString() = objectToXml(this)
}

class SingleEventConfiguration {
    /** index in the configurations list, obtain label, ... from there */
    @JacksonXmlProperty(isAttribute = true)
    var id: Int = -1

    @JacksonXmlProperty(localName = "Setting")
    lateinit var settings: List<EventSetting>

    constructor(id: Int = -1, settings: List<EventSetting> = ArrayList()) {
        this.id = id
        this.settings = settings
    }
}

enum class Period {
    @JsonProperty("everyChunk")
    EVERY_CHUNK,

    @JsonProperty("endChunk")
    END_CHUNK,
}

class Type {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    @JacksonXmlProperty(isAttribute = true)
    var label: String? = null

    @JacksonXmlProperty(localName = "Field")
    var fields: List<Field> = ArrayList()

    override fun toString() = objectToXml(this)
}

enum class Transition {
    @JsonProperty("to")
    TO,

    @JsonProperty("from")
    FROM
}

class Field {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var type: String

    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    @JacksonXmlProperty(isAttribute = true)
    lateinit var label: String

    @JacksonXmlProperty(isAttribute = true)
    var relation: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var contentType: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var description: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var struct: Boolean = false

    @JacksonXmlProperty(isAttribute = true)
    var experimental: Boolean = true

    @JacksonXmlProperty(isAttribute = true)
    var transition: Transition? = null

    @JacksonXmlProperty(isAttribute = true)
    var array: Boolean = false

    override fun toString() = objectToXml(this)
}

class Relation {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    override fun toString() = objectToXml(this)
}

class XmlType {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    @JacksonXmlProperty(isAttribute = true)
    lateinit var parameterType: String

    @JacksonXmlProperty(isAttribute = true)
    lateinit var fieldType: String

    @JacksonXmlProperty(isAttribute = true)
    var javaType: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var contentType: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var unsigned: Boolean? = null

    fun matchesJavaType(fullyQualifiedJavaType: String): Boolean {
        return javaType == fullyQualifiedJavaType
    }

    override fun toString() = objectToXml(this)
}

class XmlContentType {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    @JacksonXmlProperty(isAttribute = true)
    lateinit var annotation: String

    fun matchesAnnotation(annotationType: String, valueExpression: String, hasFrequency: Boolean = false): Boolean {
        assert(annotation.startsWith("jdk.jfr"))
        if (this.annotation.startsWith("jdk.jfr.$annotationType")) {
            val modAnn = this.annotation.substring(8).replace("(", ".").replace(")", "")
            if (hasFrequency) {
                return "$modAnn, jdk.jfr.Frequency" == valueExpression
            }
            return modAnn == valueExpression
        }
        return false
    }

    companion object {
        fun create(annotationType: String, valueExpression: String, hasFrequency: Boolean = false): XmlContentType {
            val (klass, field) = valueExpression.split(".")
            assert(klass == annotationType)
            return XmlContentType().apply {
                name = field.lowercase()
                annotation = "jdk.jfr.$annotationType($field)${if (hasFrequency) ", jdk.jfr.Frequency" else ""}"
            }
        }

        fun create(name: String, annotation: String, label: String): XmlContentType {
            return XmlContentType().apply {
                this.name = name
                this.annotation = annotation
            }
        }
    }

    override fun toString() = objectToXml(this)
}
