package org.example.models;

public class Sensor {
    public String id;
    public long time;
    public double temperature;

    public Sensor() {}

    public Sensor(String id, long time, double temperature) {
        this.id = id;
        this.time = time;
        this.temperature = temperature;
    }
}