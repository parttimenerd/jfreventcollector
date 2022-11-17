package me.bechberger.collector.xml

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path

// based on https://gist.github.com/stephenjfox/58770f7237741494f3a6aad07ce3284d

internal val kotlinXmlMapper = XmlMapper(
    JacksonXmlModule().apply {
        setDefaultUseWrapper(false)
    }
).registerKotlinModule().enable(SerializationFeature.INDENT_OUTPUT)
    .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
    .enable(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT)
    .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)

fun <T> Path.readXmlAs(clazz: Class<T>): T = kotlinXmlMapper.readValue(this.toFile(), clazz)

fun <T> Path.storeXmlAs(obj: T) = kotlinXmlMapper.writeValue(this.toFile(), obj)

fun objectToXml(obj: Any): String = kotlinXmlMapper.writeValueAsString(obj)
