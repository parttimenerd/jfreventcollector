JFR Event Collector
=====================

This project is collecting info on JFR events from JFR files and the OpenJDK source code,
producing an extended metadata file which can be used by the JFR Event Explorer.

The extended metadata includes

- all events defined in the metadata.xml file and the JDK source code
- additional descriptions: please contribute yourself
- versions of the JDK in which every event, field, ... is present
- examples for events and their fields for the renaissance benchmark with different GCs

The event collection is presented at [SapMachine](https://sap.github.io/SapMachine/jfrevents/),
created by [jfrevents-site-generator](https://github.com/parttimenerd/jfrevents-site-generator).

## Want to contribute?

Add new descriptions for events, types or fields to the `additional.xml` file.
These descriptions should explain what the described entity represents and
how it can be interpreted and used during profiling.

I pledge to try to bring added descriptions into the OpenJDK (but only JDK head seems to be feasiable, 
so contributing it here still makes sense).

## Download

- The collector JAR: 
  [here](https://github.com/parttimenerd/jfreventcollector/releases/latest/download/jfreventcollector.jar).
- The collection JAR (with all extended metadata xmls at your fingertips): 
  [here](https://github.com/parttimenerd/jfreventcollector/releases/latest/download/jfreventcollection.jar).
- The metadata itself: look no further than the releases page of this repository


## Usage of the metadata including JAR in Code
Useful when creating your own JFR Event Explorer like tool. The related JAR is called `jfreventcollection.jar`
in the releases.

```kotlin
// load and print the metadata for JDK 17
println(me.bechberger.collector.xml.Loader.load(17))
```


## Usage of the JFR processor

The default processor run by the JAR processes a JFR file and prints all available information.
```sh
# small sample (3710 events, 47 event types)
java -jar jfreventcollector.jar samples/profile.jfr
# large sample (5345369 events, 94 event types)
java -jar jfreventcollector.jar samples/flight_large.jfr
```

`flight_large.jfr` is a recording of an execution of the renaissance benchmark suite
(https://github.com/renaissance-benchmarks/renaissance).

## Usage of the Event Adder
This adds the events found in the passed JDK source folder.

```sh
java -cp jfreventcollector.jar me.bechberger.collector.EventAdderKt <path to metadata.xml> <path to OpenJDK source> <path to result xml file>
```

## Usage of the Example Adder
This adds examples from the passed JFR file to the passed metadata file.

```sh
java -cp jfreventcollector.jar me.bechberger.collector.ExampleAdderKt <path to metadata.xml> <label of file> <description of file> <JFR file> ... \
    <path to resulting metadata.xml>
```

## Usage of the SinceAdder
This adds `jdks` attributes to fields and events based on the passed metadata files.

```sh
java -cp jfreventcollector.jar me.bechberger.collector.SinceAdderKt <smallest version> <metadata file> <metadata output file> ...
```

## Usage of the AdditionalDescriptionAdder
This adds additional descriptions to events and fields based on the passed metadata files.

```sh
java -cp jfreventcollector.jar me.bechberger.collector.AdditionalDescriptionAdderKt <path to metadata.xml> \
  <path to xml file with additional descriptions> <path to resulting metadata.xml>
```

## Usage of the releaser script
This script helps to build the extended metadata file for every JDK version (starting with JDK11).
It should be run under the most recent released JDK version to obtain proper JFR examples.

## Including the library in your project

There is currently the release 0.2 available and the snapshot release is 0.3-SNAPSHOT.
```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>jfreventcollector</artifactId>
    <version>0.2</version>
</dependency>
```

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>jfreventcollection</artifactId>
    <version>0.2</version>
</dependency>
```

You might have to add the `https://s01.oss.sonatype.org/content/repositories/releases/` repo:

To use snapshots, you have to add the snapshot repository:

```xml
<repositories>
    <repository>
        <id>snapshots</id>
        <url>https://s01.oss.sonatype.org/content/repositories/snapshots/</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

## Build and Deploy

Use the `bin/releaser.sh` script:

```sh
Usage:
    python3 releaser.py <command> ... <command>

Commands:
    versions          print all available JDK versions
    download          download the latest source code for every JDK version
    build_parser      build the parser JAR
    create_jfr        create the JFR file for every available GC
    build_versions    build the extended metadata file for every available JDK version
    build             build the JAR with the extended metadata files
    deploy_mvn        deploy the JARs to Maven
    deploy_gh         deploy the JARs and XML files to GitHub
    deploy            the two above
    deploy_release    deploy the JARs and XML files to GitHub and Maven as releases
    clear             clear some folders

```


## Troubleshooting
Builds might take longer on newer maven versions due to blocking
of http resources (and I don't know which).
Maven 3.6.3 seems to work fine.

License
-------
GPLv2