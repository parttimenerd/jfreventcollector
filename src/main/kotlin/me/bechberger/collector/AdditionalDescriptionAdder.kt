package me.bechberger.collector

import me.bechberger.collector.xml.readXmlAs
import java.nio.file.Paths
import kotlin.io.path.writeText

fun main(args: Array<String>) {
    if (args.size != 3) {
        println(
            "Usage: AdditionalDescriptionAdder <path to metadata.xml> " +
                "<path to xml file with additional descriptions> <path to resulting metadata.xml>"
        )
        return
    }
    val metadataPath = Paths.get(args[0])
    val metadata = metadataPath.readXmlAs(me.bechberger.collector.xml.Metadata::class.java)
    val additionalMetadata = Paths.get(args[1]).readXmlAs(me.bechberger.collector.xml.Metadata::class.java)
    metadata.addAdditionalDescription(additionalMetadata)
    val out = args[2]
    if (out == "-") {
        println(metadata)
    } else {
        Paths.get(out).writeText(metadata.toString())
    }
}
