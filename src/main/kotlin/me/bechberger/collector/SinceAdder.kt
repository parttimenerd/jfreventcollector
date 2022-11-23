package me.bechberger.collector

/**
 * Adds since and until attributes
 */

import java.nio.file.Paths
import me.bechberger.collector.xml.readXmlAs
import kotlin.io.path.writeText

fun addSinceAndUntil(perVersion: List<Pair<Int, me.bechberger.collector.xml.Metadata>>) {
    perVersion.forEach { (_, meta) ->
        meta.setSinceAndUntil(perVersion)
    }
}

fun main(args: Array<String>) {
    if (args.size < 3 && args.size % 3 != 0) {
        println(
            "Usage: SinceAdder <smallest version> <metadata file> <metadata output file> ..."
        )
        return
    }
    val outputFiles = args.filterIndexed { index, _ -> index % 3 == 2 }
    val metadataFiles = args.filterIndexed { index, _ -> index % 3 == 1 }
        .map { Paths.get(it).readXmlAs(me.bechberger.collector.xml.Metadata::class.java) }
    val versions = args.filterIndexed { index, _ -> index % 3 == 0 }.map { it.toInt() }
    assert(versions == versions.sorted())
    val pairs: List<Pair<Int, me.bechberger.collector.xml.Metadata>> = versions.zip(metadataFiles)
    addSinceAndUntil(pairs)
    metadataFiles.forEachIndexed { index, meta ->
        Paths.get(outputFiles[index]).writeText(meta.toString())
    }
}
