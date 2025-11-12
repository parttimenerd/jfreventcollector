package me.bechberger.collector.site

import com.github.mustachejava.DefaultMustacheFactory
import com.github.mustachejava.Mustache
import com.github.mustachejava.MustacheFactory
import java.io.StringReader
import java.io.StringWriter
import java.net.URL
import java.nio.file.Path
import java.util.Enumeration
import me.bechberger.collector.xml.Loader

class Templating(private val resourceFolder: Path? = null) {

    val mf: MustacheFactory = DefaultMustacheFactory()

    val cache: MutableMap<String, Mustache> = mutableMapOf()

    /**
     * Compile the passed template (or load it from the template folder if it ends with .html)
     *
     * Does caching.
     */
    private fun compile(template: String): Mustache {
        return cache.getOrPut(template) {
            if (template.endsWith(".html")) {
                if (resourceFolder == null) {
                    val files: Enumeration<URL> =
                        Loader::class.java.classLoader.getResources("template/$template")
                    if (!files.hasMoreElements()) {
                        throw IllegalArgumentException("No template $template found")
                    }
                    val file = files.nextElement()
                    mf.compile(file.openStream().reader(), template)
                } else {
                    mf.compile(resourceFolder.resolve("template/$template").toFile().inputStream().reader(), template)
                }
            } else {
                mf.compile(StringReader(template), template)
            }
        }
    }

    fun template(template: String, scope: Any): String {
        return compile(template).execute(StringWriter(), scope).toString()
    }

    fun copyFromResources(target: Path, resource: String) {
        target.parent.toFile().mkdirs()
        if (resourceFolder == null) {
            val files: Enumeration<URL> =
                Loader::class.java.classLoader.getResources(resource)
            if (!files.hasMoreElements()) {
                throw IllegalArgumentException("No resource $resource found")
            }
            val file = files.nextElement()
            target.toFile().outputStream().use {
                it.write(file.openStream().readBytes())
            }
        } else {
            target.toFile().outputStream().use {
                it.write(resourceFolder.resolve(resource).toFile().inputStream().readBytes())
            }
        }
    }
}