JFR Event Collector
=====================

Repository for experimental work on collecting information on JFR events.

Sample usage:

```
./build.sh
# small sample (3710 events, 47 event types)
./run.sh samples/profile.jfr
# large sample (5345369 events, 94 event types)
./run.sh samples/flight_large.jfr
```

`flight_large.jfr` is a recording of an execution of the renaissance benchmark suite
(https://github.com/renaissance-benchmarks/renaissance).

License
-------
Apache 2.0