package me.bechberger.collector.site

import java.nio.file.Path

fun create(target: Path, jdk: Int = 21) {
    try {
        Main(target, Path.of("src/main/resources/")).createPage(jdk)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun main(args: Array<String>) {
    val templateFolder = Path.of("src/main/resources/template")
    val target = Path.of(args[0])
    var map = templateFolder.toFile().walk().filter { it.isFile }.map { it.toPath() to it.lastModified() }.toMap()
    create(target, if (args.size > 1) args[1].toInt() else 21)
    while (true) {
        val newMap =
            templateFolder.toFile().walk().filter { it.isFile }.map { it.toPath() to it.lastModified() }.toMap()
        if (newMap != map) {
            println("Change detected")
            create(target)
            println("Create page for JDK $target")
            map = newMap
        }
        Thread.sleep(100)
    }
}
