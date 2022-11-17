package me.bechberger.collector.xml

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText

@JacksonXmlRootElement(localName = "configuration")
@JsonIgnoreProperties(ignoreUnknown = true)
class Configuration {
    lateinit var version: String
    lateinit var label: String
    lateinit var description: String
    lateinit var provider: String

    @JacksonXmlProperty(localName = "event")
    lateinit var events: List<EventConfiguration>

    @get:JsonIgnore
    val eventMap: Map<String, EventConfiguration> by lazy {
        events.associateBy { it.name }
    }

    fun get(event: String): EventConfiguration? = eventMap[event]

    override fun toString() = objectToXml(this)
}

@JacksonXmlRootElement(localName = "event")
class EventConfiguration {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    @JacksonXmlProperty(localName = "setting")
    lateinit var settings: List<EventSetting>

    @get:JsonIgnore
    val settingMap: Map<String, EventSetting> by lazy {
        settings.associateBy { it.name }
    }

    fun get(event: String): EventSetting? = settingMap[event]

    fun contains(event: String): Boolean = settingMap.containsKey(event)

    override fun toString() = objectToXml(this)
}

@JacksonXmlRootElement(localName = "setting")
class EventSetting {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    @JacksonXmlText
    lateinit var value: String

    @JacksonXmlProperty(isAttribute = true)
    var control: String? = null

    override fun toString() = objectToXml(this)
}
