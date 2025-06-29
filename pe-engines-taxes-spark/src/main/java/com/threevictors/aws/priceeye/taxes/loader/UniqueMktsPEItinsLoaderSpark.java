package com.threevictors.aws.priceeye.taxes.loader;

import com.threevictors.common.database.dao.aurora.metadata.AuroraMetadataReader;
import lombok.Data;
import org.apache.spark.sql.*;
import org.apache.spark.sql.types.*;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.function.FilterFunction;

import java.io.*;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Spark-based implementation of UniqueMktsPEItinsLoaderConnections
 * This class processes CSV files containing flight itinerary data using Apache Spark
 * for distributed processing and improved performance.
 */
public class UniqueMktsPEItinsLoaderSpark {
    private static Map<String, String> airportCountryCodeMap;
    private static Set<String> usDomesticAirports;
    private static final long startTime = System.currentTimeMillis();

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Error: Please provide both input and output file paths as arguments.");
            System.exit(1);
        }

        // Initialize metadata
        AuroraMetadataReader metadataReader = new AuroraMetadataReader();
        usDomesticAirports = metadataReader.getAirportCountryMap().entrySet().stream().filter(entry -> "US".equals(entry.getValue()) || "CA".equals(entry.getValue())).map(Map.Entry::getKey).collect(Collectors.toSet());
        metadataReader.shutdown();

        String inputFilePath = args[0];
        String outputFilePath = args[1];

        File inputFile = new File(inputFilePath);
        File outputFile = new File(outputFilePath);

        System.out.println("Processing started with Spark...");

        // Initialize Spark
        SparkConf conf = new SparkConf()
                .setAppName("UniqueMktsPEItinsLoader")
                .setMaster("local[*]"); // Use all available cores
//                .set("spark.executor.memory", "4g")
//                .set("spark.driver.memory", "4g")
//                .set("spark.sql.shuffle.partitions", "8");

        SparkSession spark = SparkSession.builder()
                .config(conf)
                .getOrCreate();

        try {
            // Register UDFs for custom functions
            registerUDFs(spark);

            // Read the CSV file
            Dataset<Row> df = spark.read()
                    .option("header", "true")
                    .option("inferSchema", "true")
                    .csv(inputFilePath);

            // Process the data
            Dataset<Row> processedDf = processDataframe(df, spark);

            // Write the output
            processedDf.write()
                    .option("header", "true")
                    .option("delimiter", ",")
                    .mode(SaveMode.Overwrite)
                    .csv(outputFilePath + "_temp");

            // Rename the output file (Spark creates a directory with part files)
            mergeAndRenameOutput(outputFilePath + "_temp", outputFilePath);

            // Print performance statistics
            printPerformanceStats(df.count(), processedDf.count());

        } finally {
            // Stop Spark session
            spark.stop();
        }

        System.out.println("Done. Output written to " + outputFile.getAbsolutePath());
    }

    /**
     * Register user-defined functions for use in Spark SQL
     */
    private static void registerUDFs(SparkSession spark) {
        // UDF to check if an airport is in the US
        spark.udf().register("isUSAirport", (String airportCode) -> {
            if (airportCode == null || airportCode.isEmpty()) {
                return false;
            }
            return usDomesticAirports.contains(airportCode);
        }, DataTypes.BooleanType);

        // UDF to calculate hours between two date/time pairs
        spark.udf().register("hoursBetween", 
            (String departDate, String arriveTime, String arriveDate, String departTime) -> {
                try {
                    String dt1 = departDate + " " + String.format("%04d", Integer.parseInt(arriveTime));
                    String dt2 = arriveDate + " " + String.format("%04d", Integer.parseInt(departTime));

                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd HHmm");
                    LocalDateTime ldt1 = LocalDateTime.parse(dt1, formatter);
                    LocalDateTime ldt2 = LocalDateTime.parse(dt2, formatter);

                    // Calculate the absolute duration between the two times
                    Duration duration = Duration.between(ldt1, ldt2);
                    if (duration.isNegative()) {
                        duration = duration.negated();
                    }
                    return duration.toHours();
                } catch (Exception e) {
                    return 0L;
                }
            }, DataTypes.LongType);

        // UDF to generate a unique key for each itinerary
        spark.udf().register("generateKey", 
            (String carrier, 
             String obl1Orig, String obl1Dest, 
             String obl2Orig, String obl2Dest,
             String ibl1Orig, String ibl1Dest,
             String ibl2Orig, String ibl2Dest) -> {

                // Check if return airport matches origin
                if (!obl1Orig.equalsIgnoreCase(ibl2Dest)) {
                    return null;
                }

                return String.join("-",
                    carrier,
                    obl1Orig, obl1Dest,
                    obl2Orig, obl2Dest,
                    ibl1Orig, ibl1Dest,
                    ibl2Orig, ibl2Dest
                );
            }, DataTypes.StringType);
    }

    //"validatingcarrier",
    // "out1_orig","out1_dest","out1_mkt_carrier","out1_flt","out1_dep_date","out1_dep_time","out1_arr_date","out1_arr_time",
    // "out2_orig","out2_dest","out2_mkt_carrier","out2_flt","out2_dep_date","out2_dep_time","out2_arr_date","out2_arr_time",
    // "in1_orig","in1_dest","in1_mkt_carrier","in1_flt","in1_dep_date","in1_dep_time","in1_arr_date","in1_arr_time",
    // "in2_orig","in2_dest","in2_mkt_carrier","in2_flt","in2_dep_date","in2_dep_time","in2_arr_date","in2_arr_time",
    // "cabin","totalamount","taxbreakdown","yq_val","yr_val","true_tax_amount"
    /**
     * Process the dataframe to filter and transform the data
     */
    private static Dataset<Row> processDataframe(Dataset<Row> df, SparkSession spark) {
        // Use DataFrame API instead of SQL to avoid UDF registration complexity
        return df.filter((FilterFunction<Row>) row -> {
            try {
                // Extract leg data
                String obl1MktCarrier = row.getAs("out1_mkt_carrier");
                String obl2MktCarrier = row.getAs("out2_mkt_carrier");
                String ibl1MktCarrier = row.getAs("in1_mkt_carrier");
                String ibl2MktCarrier = row.getAs("in2_mkt_carrier");
                
                String obl1Dest = row.getAs("out1_dest");
                String ibl1Dest = row.getAs("in1_dest");
                
                String obl1Orig = row.getAs("out1_orig");
                String ibl2Dest = row.getAs("in2_dest");
                
                // Check for multi-carrier (must be single carrier)
                if (!obl1MktCarrier.equals(obl2MktCarrier) || 
                    !obl1MktCarrier.equals(ibl1MktCarrier) || 
                    !obl1MktCarrier.equals(ibl2MktCarrier)) {
                    return false;
                }
                
                // Check for toothy grins (origin must equal final destination)
                if (!obl1Orig.equalsIgnoreCase(ibl2Dest)) {
                    return false;
                }
                
                // Check US connecting airports and connection times
                if (isUSAirport(obl1Dest)) {
                    long hours = hoursBetween(
                        row.getAs("out1_arr_date"), row.getAs("out1_arr_time"),
                        row.getAs("out2_dep_date"), row.getAs("out2_dep_time")
                    );
                    if (hours >= 12) return false;
                }
                
                if (isUSAirport(ibl1Dest)) {
                    long hours = hoursBetween(
                        row.getAs("in1_arr_date"), row.getAs("in1_arr_time"),
                        row.getAs("in2_arr_date"), row.getAs("in2_dep_time")
                    );
                    if (hours >= 12) return false;
                }
                
                return true;
                
            } catch (Exception e) {
                return false;
            }
        }).dropDuplicates(generateKeyColumns());
    }

    /**
     * Generate key columns for deduplication
     */
    private static String[] generateKeyColumns() {
        return new String[]{
            "out1_mkt_carrier",
            "out1_orig", "out1_dest",
            "out2_orig", "out2_dest",
            "in1_orig", "in1_dest",
            "in2_orig", "in2_dest"
        };
    }

    /**
     * Helper method to check if airport is US-based
     */
    private static boolean isUSAirport(String airportCode) {
        if (airportCode == null || airportCode.isEmpty()) {
            return false;
        }
        return usDomesticAirports.contains(airportCode);
    }

    /**
     * Helper method to calculate hours between two date/time pairs
     */
    private static long hoursBetween(String departDate, String arriveTime, String arriveDate, String departTime) {
        try {
            String dt1 = departDate + " " + String.format("%04d", Integer.parseInt(arriveTime));
            String dt2 = arriveDate + " " + String.format("%04d", Integer.parseInt(departTime));

            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd HHmm");
            java.time.LocalDateTime ldt1 = java.time.LocalDateTime.parse(dt1, formatter);
            java.time.LocalDateTime ldt2 = java.time.LocalDateTime.parse(dt2, formatter);
            
            java.time.Duration duration = java.time.Duration.between(ldt1, ldt2);
            if (duration.isNegative()) {
                duration = duration.negated();
            }
            return duration.toHours();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Merge the part files created by Spark and rename to the desired output file
     */
    private static void mergeAndRenameOutput(String tempDir, String outputFilePath) throws IOException {
        // Use Java's Files API to merge the part files
        File tempDirFile = new File(tempDir);
        File[] partFiles = tempDirFile.listFiles((dir, name) -> name.startsWith("part-") && name.endsWith(".csv"));

        if (partFiles != null && partFiles.length > 0) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath))) {
                // Write header from first part file
                List<String> lines = Files.readAllLines(partFiles[0].toPath());
                if (!lines.isEmpty()) {
                    writer.write(lines.get(0));
                    writer.newLine();

                    // Write data rows from all part files
                    for (File partFile : partFiles) {
                        lines = Files.readAllLines(partFile.toPath());
                        for (int i = 1; i < lines.size(); i++) {  // Skip header (i=0)
                            writer.write(lines.get(i));
                            writer.newLine();
                        }
                    }
                }
            }

            // Clean up temp directory
            for (File file : tempDirFile.listFiles()) {
                file.delete();
            }
            tempDirFile.delete();
        } else {
            throw new IOException("No part files found in " + tempDir);
        }
    }

    /**
     * Print performance statistics
     */
    private static void printPerformanceStats(long totalLines, long processedLines) {
        long endTime = System.currentTimeMillis();
        long totalTimeSeconds = (endTime - startTime) / 1000;

        System.out.println("\n--- Performance Summary ---");
        System.out.println("Total lines read: " + totalLines);
        System.out.println("Total lines processed: " + processedLines);
        System.out.println("Total time: " + totalTimeSeconds + " seconds");

        if (totalTimeSeconds > 0) {
            System.out.println("Average processing rate: " + (totalLines / totalTimeSeconds) + " lines/sec");
        }

        // Print CPU utilization information
        System.out.println("\n--- CPU Utilization Information ---");
        System.out.println("Available processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Spark is using all available cores for processing");

        // Print memory usage information
        long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long totalMemory = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long freeMemory = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;

        System.out.println("\n--- Memory Usage Information ---");
        System.out.println("Max memory: " + maxMemory + " MB");
        System.out.println("Total memory: " + totalMemory + " MB");
        System.out.println("Used memory: " + usedMemory + " MB");
        System.out.println("Free memory: " + freeMemory + " MB");
    }

    /**
     * Leg class representing a flight leg
     */
    @Data
    static class Leg {
        String mktCarrier;
        String originAirportCode;
        String destinationAirportCode;
        String departDate;
        String departTime;
        String arriveDate;
        String arriveTime;
    }
}