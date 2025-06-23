# Sensor Simulator + Flink Temperature Averaging

This project simulates multiple sensors writing temperature readings to a file, and processes those readings using Apache Flink to compute the global average temperature every minute.

---

## 🚀 Prerequisites

- Java 17 ( Must )
- Maven
- Apache Flink 1.17.x ( Make sure installed! )
- Bash or compatible shell

---
## 📦 Build the Project

1. Clone the repository and `cd` into it.
2. Package the JAR with dependencies:

```bash
mvn clean package
```

---
##  Step 1: Start Sensor Simulator
1. in the first terminal (run sensor job):

- default
```bash
java -cp target/sensor-simulator-1.0-SNAPSHOT.jar org.example.Main
```
- custom
```bash
  java -cp target/sensor-simulator-1.0-SNAPSHOT.jar org.example.Main 5 20
```

Creates data/sensors.logs and appends data continuously.

---
## Step 2: Run Apache Flink

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
cd ~/flink
./bin/start-cluster.sh
```
if flink server need restart due to limit number of jobs run:
```bash
./bin/stop-cluster.sh
```
if Java 17 is not installed run 


```bash
brew install openjdk@17
```
Visit http://localhost:8081 to confirm it's running.

if http is not responding check:

```bash
jps
```
You should see:
```bash
StandaloneSessionClusterEntrypoint
TaskManagerRunner
```
If you don’t see those two processes, the cluster is not running correctly.

(Check if java verison is 17 and NOT ABOVE)



---
## Step 3: Submit the Flink Job
1. In the second terminal run the flink job

```bash
flink run -c org.example.FlinkTemperatureJob target/sensor-simulator-1.0-SNAPSHOT.jar 
```

and should see:

```bash
Job has been submitted with JobID 4e7d52ad6d9013c450d675aebdc1aef1
```
- with different jobID
-  Output is written every minute to: global_avg.log

Log example:
```bash
Timestamp: 1750689300000, Global Average Temperature: 30.6 C
```
To change to path of the printed directory

```bash
public static class PrintGlobalAverage extends ProcessAllWindowFunction<Double, Void, TimeWindow> {
        @Override
        public void process(Context context, Iterable<Double> input, Collector<Void> out) {
            long windowStart = context.window().getStart();
            double average = input.iterator().next();

            try (FileWriter fw = new FileWriter("/USER_PROJECT_FULL_PATH/IdeaProjects/untitled/data/global_avg.log", true)) {
                fw.write(String.format("Timestamp: %d, Global Average Temperature: %.1f C%n", windowStart, average));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
```
To get the full path run
```bash
realpath data/YOUR_FILE_NAME // /Users/username/IdeaProjects/untitled/data/sensors.logs(something like this)
```

same thing here:
```bash
final String inputPath = "/Users/username/IdeaProjects/untitled/data/sensors.logs";

```
---
## Cleanup
Stop Flink when you're done:

```bash
cd ~/flink
./bin/stop-cluster.sh
```