package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.models.Sensor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.Random;

public class Main {

    public static void main(String[] args) throws Exception {
        // Use default values if args are not provided
        int numDevices = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        int readingsPerMinute = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        long intervalMillis = 60000 / readingsPerMinute;


        // Ensure output directory exists
        File directory = new File("data");
        if (!directory.exists()) {
            directory.mkdirs();
        }

        // Open file for appending
        File logFile = new File(directory, "sensors.logs");
        Writer writer = new BufferedWriter(new FileWriter(logFile, true));

        System.out.println("Logging sensor data to data/sensors.logs...");
        System.out.println("Simulating " + numDevices + " sensors, each generating "
                + readingsPerMinute + " readings per minute (" + intervalMillis + " ms interval)");

        ObjectMapper mapper = new ObjectMapper();
        Random random = new Random();

        while (true) {
            for (int i = 0; i < readingsPerMinute; i++) {
                long now = Instant.now().toEpochMilli();

                for (int j = 0; j < numDevices; j++) {
                    double temperature = 10 + random.nextDouble() * 40;
                    Sensor sensor = new Sensor("0x" + Integer.toHexString(j), now, temperature);
                    writer.write(mapper.writeValueAsString(sensor) + "\n");
                }

                writer.flush();
                Thread.sleep(intervalMillis);
            }
        }
    }
}
