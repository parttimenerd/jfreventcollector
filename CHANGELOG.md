# JFR Event Collector

## [0.6]

### Added
- GraalVM events
- StackFilter annotations, which are used to filter out methods/classes from the stack traces, as event field stackFilter

### Changed
- Period enum changed to raw string to be more flexible

## [0.5]

### Added
- The ability to generate additional description for every event via GPT3.5

## [0.4]

### Added
- `fakeEndTime` field to mark `endTime` values as fake (equal to `startTime` on purpose)
- List of supporting JDKs to every event and type

### Changed
- Updated data set to include the latest JDK update releases

## [0.3]

### Added
- "tags" command to show these releases per JDK version

### Changed
- Updated data set to include the latest JDK update releases

## [0.2]

### Changed
- since/until to just a lists of JDKs (`jdks`)

## [0.1]

### Added
- Initial release