package me.bechberger.collector.xml

/**
 * Contains the XML mapper classes for the JFR configuration file
 */

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

    var since: Int? = null
    var until: Int? = null

    @JacksonXmlProperty(localName = "event")
    lateinit var events: List<EventConfiguration>

    @get:JsonIgnore
    val eventMap: Map<String, EventConfiguration> by lazy {
        events.associateBy { it.name }
    }

    /** works with 'jdk.Event' and 'Event' */
    fun get(event: String): EventConfiguration? = eventMap[event] ?: eventMap["jdk.$event"]

    override fun toString() = objectToXml(this)

    fun setSinceAndUntil(perVersion: List<Pair<Int, Configuration?>>) {
        since = perVersion.firstOrNull { it.second != null }?.first
        until = perVersion.lastOrNull { it.second != null }?.first
        events.forEach { e -> e.setSinceAndUntil(perVersion.map { (v, c) -> c?.get(e.name) }) }
    }
}

@JacksonXmlRootElement(localName = "event")
class EventConfiguration {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    @JacksonXmlProperty(localName = "setting")
    lateinit var settings: List<EventSetting>

    @JacksonXmlProperty(isAttribute = true)
    var since: Int? = null

    @JacksonXmlProperty(isAttribute = true)
    var until: Int? = null

    @get:JsonIgnore
    val settingMap: Map<String, EventSetting> by lazy {
        settings.associateBy { it.name }
    }

    fun get(event: String): EventSetting? = settingMap[event]

    fun contains(event: String): Boolean = settingMap.containsKey(event)

    override fun toString() = objectToXml(this)

    fun setSinceAndUntil(perVersion: List<EventConfiguration?>) {
        since = perVersion.firstOrNull { it != null }?.since
        until = perVersion.lastOrNull { it != null }?.until
        settings.forEach { s -> s.setSinceAndUntil(perVersion.map { it?.get(s.name) }) }
    }
}

@JacksonXmlRootElement(localName = "setting")
class EventSetting {
    @JacksonXmlProperty(isAttribute = true)
    lateinit var name: String

    @JacksonXmlText
    lateinit var value: String

    @JacksonXmlProperty(isAttribute = true)
    var control: String? = null

    @JacksonXmlProperty(isAttribute = true)
    var since: Int? = null

    @JacksonXmlProperty(isAttribute = true)
    var until: Int? = null

    override fun toString() = objectToXml(this)

    fun setSinceAndUntil(perVersion: List<EventSetting?>) {
        since = perVersion.firstOrNull { it != null }?.since
        until = perVersion.lastOrNull { it != null }?.until
    }
}
