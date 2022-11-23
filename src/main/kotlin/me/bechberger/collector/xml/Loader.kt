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
        val versions = mutableListOf<Int>()
        try {
            val indexFiles: Enumeration<URL> =
                Loader::class.java.classLoader.getResources("metadata/versions")
            while (indexFiles.hasMoreElements()) {
                val indexFile = indexFiles.nextElement()
                BufferedReader(InputStreamReader(indexFile.openStream())).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        versions.add(line.toInt())
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return emptyList()
        }
        return versions
    }

    /** Load the metadata for a specific version */
    fun loadVersion(version: Int): Metadata {
        val files: Enumeration<URL> =
            Loader::class.java.classLoader.getResources("metadata/metadata_$version.xml")
        if (!files.hasMoreElements()) {
            throw IllegalArgumentException("No metadata file for version $version found")
        }
        val file = files.nextElement()
        return kotlinXmlMapper.readValue(file.openStream(), Metadata::class.java)
    }
}
