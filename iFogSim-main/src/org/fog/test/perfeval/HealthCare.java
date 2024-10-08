package org.fog.test.perfeval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.*;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.entities.*;
import org.fog.entities.MicroserviceFogDevice;
import org.fog.entities.PlacementRequest;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.placement.ModulePlacementMapping;
import org.fog.placement.MicroservicesController;
import org.fog.placement.PlacementLogicFactory;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;


public class HealthCare {

    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor> sensors = new ArrayList<>();
    static List<Actuator> actuators = new ArrayList<>();
    static Scanner sc = new Scanner(System.in);
    //System.out.println(" Number of Areas: ");
    static int numOfAreas =sc.nextInt() ;
    //System.out.println(" Number of Sensors per area: ");
    static int numOfSensorsPerArea = sc.nextInt();

    private static boolean CLOUD = true;

    public static void main(String[] args) {
        Log.printLine("Starting health monitoring system...");

        try {
            Log.enable(); // Enable logging
            int num_user = 1;
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false;
            CloudSim.init(num_user, calendar, trace_flag);

            String appId = "health-monitoring";
            FogBroker broker = new FogBroker("broker");
            Application application = createApplication(appId, broker.getId());
            application.setUserId(broker.getId());

            createFogDevices(broker.getId(), appId);
            Controller controller;
            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();

            if (CLOUD) {
                moduleMapping.addModuleToDevice("PIR-analysis", "cloud");
                moduleMapping.addModuleToDevice("Camera-analysis", "cloud");
                moduleMapping.addModuleToDevice("HeartRate-analysis", "cloud");
                moduleMapping.addModuleToDevice("RespirationRate-analysis", "cloud");
                moduleMapping.addModuleToDevice("BloodPressure-analysis", "cloud");
            } else {
                moduleMapping.addModuleToDevice("PIR-analysis", "proxy-server");
                moduleMapping.addModuleToDevice("Camera-analysis", "proxy-server");
                moduleMapping.addModuleToDevice("HeartRate-analysis", "proxy-server");
                moduleMapping.addModuleToDevice("RespirationRate-analysis", "proxy-server");
                moduleMapping.addModuleToDevice("BloodPressure-analysis", "proxy-server");
            }

            controller = new Controller("master-controller", fogDevices, sensors, actuators);
            controller.submitApplication(application,
                    CLOUD ? new ModulePlacementMapping(fogDevices, application, moduleMapping)
                           : new ModulePlacementEdgewards(fogDevices, sensors, actuators, application, moduleMapping));

            TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
            CloudSim.startSimulation();

            // Simulate sensor readings and data processing
            simulateSensorReadings();

            CloudSim.stopSimulation();

            Log.printLine("Health monitoring finished!");
            displayResults();

        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("Unwanted errors occurred");
        }
    }

    private static void simulateSensorReadings() {
        Random rand = new Random();

        for (Sensor sensor : sensors) {
            // Simulate getting a reading
            double responseTime = 100 + rand.nextDouble() * 50; // Simulated response time
            sensor.recordReading(responseTime); // Record the response time

            // Assume each reading sends 10 MB of data
            double dataSent = 10; // in MB
            double dataReceived = 5; // in MB (for example, data received from fog device)

            // Update the corresponding fog device
            FogDevice parentDevice = findParentFogDevice(sensor); // Implement this to find the device
            if (parentDevice != null) {
                parentDevice.updateDataSent(dataSent);
                parentDevice.updateDataReceived(dataReceived);
                parentDevice.updateProcessingTime(responseTime); // Update processing time
            }
        }
    }

    private static FogDevice findParentFogDevice(Sensor sensor) {
        for (FogDevice device : fogDevices) {
            if (device.getId() == sensor.getGatewayDeviceId()) {
                return device;
            }
        }
        return null; // Return null if no parent device is found
    }

    private static void createFogDevices(int userId, String appId) {
        FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.0, 0.0);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        FogDevice proxy = createFogDevice("proxy-server", 2800, 4000, 10000, 10000, 1, 0.0, 0.0);
        proxy.setParentId(cloud.getId());
        proxy.setUplinkLatency(100);
        fogDevices.add(proxy);

        for (int i = 0; i < numOfAreas; i++) {
            addArea(Integer.toString(i), userId, appId, proxy.getId());
        }
    }

    private static void addArea(String id, int userId, String appId, int parentId) {
        FogDevice router = createFogDevice("a-" + id, 2800, 4000, 10000, 10000, 2, 0.0, 0.0);
        router.setUplinkLatency(2);
        fogDevices.add(router);

        for (int i = 0; i < numOfSensorsPerArea; i++) {
            String sensorId = id + "-" + i;
            FogDevice sensorNode = addSensor(sensorId, userId, appId, router.getId());
            fogDevices.add(sensorNode);
        }
        router.setParentId(parentId);
    }

    private static FogDevice addSensor(String id, int userId, String appId, int parentId) {
        FogDevice sensorNode = createFogDevice("s-" + id, 500, 1000, 10000, 10000, 3, 0.0, 0.0);
        sensorNode.setParentId(parentId);

        Sensor pirSensor = new Sensor("PIR-" + id, "PIR", userId, appId, new DeterministicDistribution(100));
        Sensor cameraSensor = new Sensor("Camera-" + id, "CAMERA", userId, appId, new DeterministicDistribution(100));
        Sensor heartRateSensor = new Sensor("HeartRate-" + id, "HEART_RATE", userId, appId, new DeterministicDistribution(100));
        Sensor respirationRateSensor = new Sensor("RespirationRate-" + id, "RESPIRATION_RATE", userId, appId, new DeterministicDistribution(100));
        Sensor bloodPressureSensor = new Sensor("BloodPressure-" + id, "BLOOD_PRESSURE", userId, appId, new DeterministicDistribution(100));

        sensors.add(pirSensor);
        sensors.add(cameraSensor);
        sensors.add(heartRateSensor);
        sensors.add(respirationRateSensor);
        sensors.add(bloodPressureSensor);

        pirSensor.setGatewayDeviceId(sensorNode.getId());
        cameraSensor.setGatewayDeviceId(sensorNode.getId());
        heartRateSensor.setGatewayDeviceId(sensorNode.getId());
        respirationRateSensor.setGatewayDeviceId(sensorNode.getId());
        bloodPressureSensor.setGatewayDeviceId(sensorNode.getId());

        pirSensor.setLatency(1.0);
        cameraSensor.setLatency(1.0);
        heartRateSensor.setLatency(1.0);
        respirationRateSensor.setLatency(1.0);
        bloodPressureSensor.setLatency(1.0);

        return sensorNode;
    }

    private static FogDevice createFogDevice(String nodeName, long mips, int ram, long upBw, long downBw, int level, double busyPower, double idlePower) {
        List<Pe> peList = new ArrayList<>();
        double busyPower1 = 50.0; // Example busy power in watts
        double idlePower1 = 10.0;  // Example idle power in watts
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        int hostId = FogUtils.generateEntityId();
        long storage = 1000000; // Define storage capacity
        int bw = 10000; // Define bandwidth

        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(bw),
                storage,
                peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower1, idlePower1)
        );

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        String arch = "x86"; // Architecture
        String os = "Linux"; // Operating system
        String vmm = "Xen";  // Virtual machine monitor
        double time_zone = 10.0; // Time zone offset
        double cost = 3.0; // Cost of the device
        double costPerMem = 0.05; // Cost per memory unit
        double costPerStorage = 0.001; // Cost per storage unit
        double costPerBw = 0.0; // Cost per bandwidth unit

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                arch, os, vmm, host, time_zone, cost, costPerMem, costPerStorage, costPerBw
        );

        FogDevice fogDevice = null;
        try {
            fogDevice = new FogDevice(nodeName, characteristics, new AppModuleAllocationPolicy(hostList), new LinkedList<>(), 10, upBw, downBw, 0, 0.0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        fogDevice.setLevel(level);
        return fogDevice;
    }

    private static Application createApplication(String appId, int userId) {
        Application application = Application.createApplication(appId, userId);

        application.addAppModule("PIR-analysis", 10);
        application.addAppModule("Camera-analysis", 10);
        application.addAppModule("HeartRate-analysis", 10);
        application.addAppModule("RespirationRate-analysis", 10);
        application.addAppModule("BloodPressure-analysis", 10);

        application.addAppEdge("PIR", "PIR-analysis", 1000, 500, "PIR_DATA", Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("CAMERA", "Camera-analysis", 1000, 500, "CAMERA_DATA", Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("HEART_RATE", "HeartRate-analysis", 1000, 500, "HEART_RATE_DATA", Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("RESPIRATION_RATE", "RespirationRate-analysis", 1000, 500, "RESPIRATION_RATE_DATA", Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("BLOOD_PRESSURE", "BloodPressure-analysis", 1000, 500, "BLOOD_PRESSURE_DATA", Tuple.UP, AppEdge.SENSOR);

        application.addTupleMapping("PIR-analysis", "PIR_DATA", "PIR_DATA", new FractionalSelectivity(1.0));
        application.addTupleMapping("Camera-analysis", "CAMERA_DATA", "CAMERA_DATA", new FractionalSelectivity(1.0));
        application.addTupleMapping("HeartRate-analysis", "HEART_RATE_DATA", "HEART_RATE_DATA", new FractionalSelectivity(1.0));
        application.addTupleMapping("RespirationRate-analysis", "RESPIRATION_RATE_DATA", "RESPIRATION_RATE_DATA", new FractionalSelectivity(1.0));
        application.addTupleMapping("BloodPressure-analysis", "BLOOD_PRESSURE_DATA", "BLOOD_PRESSURE_DATA", new FractionalSelectivity(1.0));

        List<AppLoop> loops = new ArrayList<>();
        loops.add(new AppLoop(Arrays.asList("PIR", "PIR-analysis")));
        loops.add(new AppLoop(Arrays.asList("CAMERA", "Camera-analysis")));
        loops.add(new AppLoop(Arrays.asList("HEART_RATE", "HeartRate-analysis")));
        loops.add(new AppLoop(Arrays.asList("RESPIRATION_RATE", "RespirationRate-analysis")));
        loops.add(new AppLoop(Arrays.asList("BLOOD_PRESSURE", "BloodPressure-analysis")));

        application.setLoops(loops);
        return application;
    }

    private static void displayResults() {
        Log.printLine("Results:");
        Log.printLine("Total Devices: " + fogDevices.size());
        Log.printLine("Total Sensors: " + sensors.size());

        double totalProcessingTime = 0.0;
        double totalDataSent = 0.0;
        double totalDataReceived = 0.0;

        for (FogDevice device : fogDevices) {
            double processingTime = device.getTotalProcessingTime();
            double dataSent = device.getTotalDataSent();
            double dataReceived = device.getTotalDataReceived();

            Log.printLine("Device ID: " + device.getId() + 
                          ", Name: " + device.getName() + 
                          ", Level: " + device.getLevel() + 
                          ", Processing Time: " + processingTime + 
                          " ms, Data Sent: " + dataSent + 
                          " MB, Data Received: " + dataReceived + " MB");

            // Accumulate totals
            totalProcessingTime += processingTime;
            totalDataSent += dataSent;
            totalDataReceived += dataReceived;
        }

        // Print overall metrics
        Log.printLine("Total Processing Time: " + totalProcessingTime + " ms");
        Log.printLine("Total Data Sent: " + totalDataSent + " MB");
        Log.printLine("Total Data Received: " + totalDataReceived + " MB");

        // Sensor-specific metrics
        for (Sensor sensor : sensors) {
            int totalReadings = sensor.getTotalReadings(); // Assuming this method exists
            double avgResponseTime = sensor.getAvgResponseTime(); // Assuming this method exists
            
            Log.printLine("Sensor ID: " + sensor.getId() + 
                          ", Type: " + sensor.getType() + 
                          ", Total Readings: " + totalReadings + 
                          ", Avg Response Time: " + avgResponseTime + " ms");
        }
        
        for (FogDevice device : fogDevices) {
            Log.printLine("Device ID: " + device.getId() + 
                          ", Energy Consumed: " + device.getEnergyConsumption() + " J");
        }

    }
}
