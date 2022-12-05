package me.bechberger.collector.xml

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path

abstract class WithJDKs<T> {
    @JacksonXmlProperty(isAttribute = true, localName = "jdks")
    var jdksBacking: String = ""

    /** In which JDKs it is defined, sorted ascendingly */
    @get:JsonIgnore
    var jdks: List<Int>
        get() = jdksBacking.split(",").map { Integer.parseInt(it) }
        set(value) {
            if (value.isEmpty()) {
                System.err.println("Error")
            }
            jdksBacking = value.joinToString(",") { it.toString() }
        }

    open fun setSupportedJDKs(perVersion: List<Pair<Int, T?>>) {
        jdks = perVersion.filter { it.second != null }.map { it.first }.sorted()
    }
}

// based on https://gist.github.com/stephenjfox/58770f7237741494f3a6aad07ce3284d

internal val kotlinXmlMapper = XmlMapper(
    JacksonXmlModule().apply {
        setDefaultUseWrapper(false)
    }
).registerKotlinModule().enable(SerializationFeature.INDENT_OUTPUT)
    .enable(DeserializationFeature.EAGER_DESERIALIZER_FETCH)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)

fun <T> Path.readXmlAs(clazz: Class<T>): T = kotlinXmlMapper.readValue(this.toFile(), clazz)

fun <T> Path.storeXmlAs(obj: T) = kotlinXmlMapper.writeValue(this.toFile(), obj)

fun objectToXml(obj: Any): String = kotlinXmlMapper.writeValueAsString(obj)
