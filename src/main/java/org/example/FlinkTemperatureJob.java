package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.*;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.io.TextInputFormat;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.BroadcastProcessFunction;
import org.apache.flink.streaming.api.functions.source.FileProcessingMode;
import org.apache.flink.streaming.api.functions.windowing.ProcessAllWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.example.models.Sensor;



import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class FlinkTemperatureJob {

    public static void main(String[] args) throws Exception {
        final String inputPath = "/Users/ericmovchan/IdeaProjects/untitled/data/sensors.logs";

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        TextInputFormat format = new TextInputFormat(new Path(inputPath));

        DataStream<String> lines = env.readFile(
                format,
                inputPath,
                FileProcessingMode.PROCESS_CONTINUOUSLY,
                1000 // check every second
        );

        ObjectMapper mapper = new ObjectMapper();

        DataStream<Sensor> sensorData = lines
                .map(line -> mapper.readValue(line, Sensor.class))
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<Sensor>forBoundedOutOfOrderness(java.time.Duration.ofSeconds(10))
                                .withTimestampAssigner((event, timestamp) -> event.time)
                );

        // Global average
        sensorData
                .windowAll(TumblingEventTimeWindows.of(Time.minutes(1)))
                .aggregate(new AverageTempAggregator(), new PrintGlobalAverage())
                .print();

        // Per-sensor average
        sensorData
                .keyBy(sensor -> sensor.id)
                .window(TumblingEventTimeWindows.of(Time.minutes(1)))
                .aggregate(new AverageTempAggregator(), new PrintPerSensorAverage())
                .print();

        MapStateDescriptor<String, Tuple3<Long, Double, Double>> statsDescriptor =
                new MapStateDescriptor<>(
                        "stats",
                        Types.STRING,
                        Types.TUPLE(Types.LONG, Types.DOUBLE, Types.DOUBLE)
                );

        DataStream<Tuple3<Long, Double, Double>> globalStats = sensorData
                .windowAll(TumblingEventTimeWindows.of(Time.minutes(1)))
                .aggregate(new StatsAggregator());

        sensorData
                .connect(globalStats.broadcast(statsDescriptor))
                .process(new AnomalyDetector(statsDescriptor));


        env.execute("Flink Global Temperature Averaging Job");
    }

    // Custom accumulator
    public static class TempAccumulator {
        double sum = 0;
        long count = 0;
    }

    public static class StatsAccumulator {
        double sum = 0;
        double sumSquares = 0;
        long count = 0;
    }

    public static class AverageTempAggregator implements AggregateFunction<Sensor, TempAccumulator, Double> {

        @Override
        public TempAccumulator createAccumulator() {
            return new TempAccumulator();
        }

        @Override
        public TempAccumulator add(Sensor value, TempAccumulator acc) {
            acc.sum += value.temperature;
            acc.count++;
            return acc;
        }

        @Override
        public Double getResult(TempAccumulator acc) {
            return acc.count == 0 ? 0 : acc.sum / acc.count;
        }

        @Override
        public TempAccumulator merge(TempAccumulator a, TempAccumulator b) {
            a.sum += b.sum;
            a.count += b.count;
            return a;
        }
    }

    public static class PrintPerSensorAverage extends ProcessWindowFunction<Double, String, String, TimeWindow> {
        @Override
        public void process(String key, Context context, Iterable<Double> input, Collector<String> out) {
            long windowStart = context.window().getStart();
            double average = input.iterator().next();

            String line = String.format(
                    "Timestamp: %d, Device: %s, Average Temperature: %.1f C",
                    windowStart, key, average
            );

            try (FileWriter writer = new FileWriter("/Users/ericmovchan/IdeaProjects/untitled/data/individual_avg.log", true)) {
                writer.write(line + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }

            out.collect(line);
        }
    }

    public static class PrintGlobalAverage extends ProcessAllWindowFunction<Double, Void, TimeWindow> {
        @Override
        public void process(Context context, Iterable<Double> input, Collector<Void> out) {
            long windowStart = context.window().getStart();
            double average = input.iterator().next();

            try (FileWriter fw = new FileWriter("/Users/ericmovchan/IdeaProjects/untitled/data/global_avg.log", true)) {
                fw.write(String.format("Timestamp: %d, Global Average Temperature: %.1f C%n", windowStart, average));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static class StatsAggregator implements AggregateFunction<Sensor, StatsAccumulator, Tuple3<Long, Double, Double>> {
        @Override
        public StatsAccumulator createAccumulator() {
            return new StatsAccumulator();
        }

        @Override
        public StatsAccumulator add(Sensor value, StatsAccumulator acc) {
            acc.sum += value.temperature;
            acc.sumSquares += value.temperature * value.temperature;
            acc.count++;
            return acc;
        }

        @Override
        public Tuple3<Long, Double, Double> getResult(StatsAccumulator acc) {
            double mean = acc.sum / acc.count;
            double variance = (acc.sumSquares / acc.count) - (mean * mean);
            double stdDev = Math.sqrt(variance);
            return Tuple3.of(acc.count, mean, stdDev);
        }

        @Override
        public StatsAccumulator merge(StatsAccumulator a, StatsAccumulator b) {
            a.sum += b.sum;
            a.sumSquares += b.sumSquares;
            a.count += b.count;
            return a;
        }
    }

    public static class AnomalyDetector extends BroadcastProcessFunction<Sensor, Tuple3<Long, Double, Double>, Void> {

        private final MapStateDescriptor<String, Tuple3<Long, Double, Double>> descriptor;

        public AnomalyDetector(MapStateDescriptor<String, Tuple3<Long, Double, Double>> descriptor) {
            this.descriptor = descriptor;
        }

        private final Set<String> reportedAnomalies = new HashSet<>();

        @Override
        public void processElement(Sensor sensor, ReadOnlyContext ctx, Collector<Void> out) throws Exception {
            Tuple3<Long, Double, Double> stats = ctx.getBroadcastState(descriptor).get("globalStats");

            if (stats != null) {
                double mean = stats.f1;
                double std = stats.f2;

                if (Math.abs(sensor.temperature - mean) > 3 * std) {
                    // Unique anomaly key
                    String key = sensor.id + "_" + sensor.time;

                    if (!reportedAnomalies.contains(key)) {
                        reportedAnomalies.add(key);

                        String log = String.format(
                                "Anomaly Detected! Timestamp: %d, Device: %s,%nAnomalous Temperature: %.1f C (Minute Avg: %.1f C, StdDev: %.1f)%n",
                                sensor.time, sensor.id, sensor.temperature, mean, std);

                        try (FileWriter fw = new FileWriter("/Users/ericmovchan/IdeaProjects/untitled/data/anomaly.log", true)) {
                            fw.write(log);
                        }
                    }
                }
            }
        }


        @Override
        public void processBroadcastElement(Tuple3<Long, Double, Double> stats, Context ctx, Collector<Void> out) throws Exception {
            ctx.getBroadcastState(descriptor).put("globalStats", stats);
        }
    }

}