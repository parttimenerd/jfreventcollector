package me.bechberger.collector.xml

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.util.Enumeration

/** Loads the included extended metadata files */
object Loader {

    /** Get Java versions */
    fun getVersions(): List<Int> {
        return getLines("metadata/versions").map { it.toInt() }
    }

    /** Java version to specific version tag */
    fun getSpecificVersions(): Map<Int, String> {
        return getLines("metadata/specific_versions").map { it.split(": ") }.map { it[0].toInt() to it[1] }.toMap()
    }

    private fun getLines(file: String): List<String> {
        val lines = mutableListOf<String>()
        try {
            val indexFiles: Enumeration<URL> =
                Loader::class.java.classLoader.getResources(file)
            while (indexFiles.hasMoreElements()) {
                val indexFile = indexFiles.nextElement()
                BufferedReader(InputStreamReader(indexFile.openStream())).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        lines.add(line)
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return emptyList()
        }
        return lines
    }

    /** Load the metadata for a specific version */
    fun loadVersion(version: Int): Metadata {
        val files: Enumeration<URL> =
            Loader::class.java.classLoader.getResources("metadata/metadata_$version.xml")
        assert(files.hasMoreElements()) {
            "No metadata file for version $version found"
        }
        val file = files.nextElement()
        return kotlinXmlMapper.readValue(file.openStream(), Metadata::class.java)
    }
}
