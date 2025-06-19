package com.threevictors.aws.priceeye.exp.loader;

import com.threevictors.aws.priceeye.exp.dao.MetadataReader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

//INPUT CSV DATA GENERATION CLASS for data with connections
//NOTE: Run this class to generate the unique itineraries from the large input files.
// The output will be written to a text file in the specified directory.
public class UniqueMktsPEItinsLoaderConnections {
    private static final Set<String> seenKeys = ConcurrentHashMap.newKeySet();
    private static Map<String, String> airportCountryCodeMap;
    private static Set<String> usDomesticAirports;

    public static void main(String[] args) throws IOException {


        if (args.length < 2) {
            System.err.println("Error: Please provide both input and output file paths as arguments.");
            System.exit(1);
        }

        MetadataReader metadataReader = new MetadataReader();
        //Contains the airport code to country code mapping for US domestic (US,PR,VI) flights only
        airportCountryCodeMap = metadataReader.getAirportCountryMapUSDomesticOnly();
        // Extract the keys (airport codes) from the map
        usDomesticAirports = airportCountryCodeMap.keySet();

        String inputFilePath = args[0];
        String outputFilePath = args[1];

        File inputFile = new File(inputFilePath);
        File outputFile = new File(outputFilePath);

        System.out.println("Processing started...");

        try (
                BufferedReader reader = new BufferedReader(new FileReader(inputFile));
                BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))
        ) {
            String header = reader.readLine();
            if (header != null) {
                writer.write(header);
                writer.newLine();
            }

            Map<String, Integer> headerMap = createHeaderIndexMap(header);

            String line;
            long count = 0;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",", -1);
                String key = generateKey(values, headerMap);

                if (key != null && seenKeys.add(key)) {
                    writer.write(line);
                    writer.newLine();
                }

                if (++count % 1_000_000 == 0) {
                    System.out.println("Processed lines: " + count);
                }
            }

            writer.flush();
        } catch (IOException e) {
            System.err.println("Error during processing: " + e.getMessage());
            System.exit(1);
        }

        System.out.println("Done. Output written to " + outputFile.getAbsolutePath());
    }

    private static Map<String, Integer> createHeaderIndexMap(String headerLine) {
        Map<String, Integer> map = new HashMap<>();
        String[] headers = headerLine.split(",");
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].trim(), i);
        }
        return map;
    }

    private static String generateKey(String[] values, Map<String, Integer> headerMap) {
        //Note: To toss out all the open jaw itineraries,
        // we need to ensure that all the marketed carriers in each of the legs are the same.
        try {
            String obl1MktCarrier = values[headerMap.get("OBL1_mkt_carrier")].trim();
            String obl2MktCarrier = values[headerMap.get("OBL2_mkt_carrier")].trim();
            String ibl1MktCarrier = values[headerMap.get("IBL1_mkt_carrier")].trim();
            String ibl2MktCarrier = values[headerMap.get("IBL2_mkt_carrier")].trim();

            // --- 12 hour stopover logic ---
            // Outbound stopover check
            String obl1Dest = values[headerMap.get("OBL1_destinationairportcode")].trim();
            String obl2Orig = values[headerMap.get("OBL2_originairportcode")].trim();
            boolean oblStopoverUS = isUSAirport(obl1Dest) && isUSAirport(obl2Orig);
            if (oblStopoverUS) {
                String obl1ArrDate = values[headerMap.get("OBL1_arrivedate")].trim();
                String obl1ArrTime = values[headerMap.get("OBL1_arrivetime")].trim();
                String obl2DepDate = values[headerMap.get("OBL2_departdate")].trim();
                String obl2DepTime = values[headerMap.get("OBL2_departtime")].trim();
                if (hoursBetween(obl1ArrDate, obl1ArrTime, obl2DepDate, obl2DepTime) > 12) {
                    return null;
                }
            }
            // Inbound stopover check
            String ibl1Dest = values[headerMap.get("IBL1_destinationairportcode")].trim();
            String ibl2Orig = values[headerMap.get("IBL2_originairportcode")].trim();
            boolean iblStopoverUS = isUSAirport(ibl1Dest) && isUSAirport(ibl2Orig);
            if (iblStopoverUS) {
                String ibl1ArrDate = values[headerMap.get("IBL1_arrivedate")].trim();
                String ibl1ArrTime = values[headerMap.get("IBL1_arrivetime")].trim();
                String ibl2DepDate = values[headerMap.get("IBL2_departdate")].trim();
                String ibl2DepTime = values[headerMap.get("IBL2_departtime")].trim();
                if (hoursBetween(ibl1ArrDate, ibl1ArrTime, ibl2DepDate, ibl2DepTime) > 12) {
                    return null;
                }
            }
            // --- End 12 hour stopover logic ---

            if (obl1MktCarrier.equalsIgnoreCase(obl2MktCarrier) &&
                    obl1MktCarrier.equalsIgnoreCase(ibl1MktCarrier) &&
                    obl1MktCarrier.equalsIgnoreCase(ibl2MktCarrier)) {
                return String.join("-",
                        values[headerMap.get("itin_validatingcarrier")].trim(),

                        values[headerMap.get("OBL1_originairportcode")].trim(),
                        values[headerMap.get("OBL1_destinationairportcode")].trim(),
                        obl1MktCarrier,

                        values[headerMap.get("OBL2_originairportcode")].trim(),
                        values[headerMap.get("OBL2_destinationairportcode")].trim(),
                        obl2MktCarrier,

                        values[headerMap.get("IBL1_originairportcode")].trim(),
                        values[headerMap.get("IBL1_destinationairportcode")].trim(),
                        ibl1MktCarrier,

                        values[headerMap.get("IBL2_originairportcode")].trim(),
                        values[headerMap.get("IBL2_destinationairportcode")].trim(),
                        ibl2MktCarrier
                );
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // Helper to check if airport is US-based
    private static boolean isUSAirport(String airportCode) {
        if (airportCode == null || airportCode.isEmpty())  {
            return false;
        }
        return usDomesticAirports.contains(airportCode);
    }

    // Helper to calculate hours between two date/time pairs (format: yyyy-MM-dd, HH:mm)
    private static long hoursBetween(String date1, String time1, String date2, String time2) {
        try {
            String dt1 = date1 + " " + time1;
            String dt2 = date2 + " " + time2;
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            java.time.LocalDateTime ldt1 = java.time.LocalDateTime.parse(dt1, formatter);
            java.time.LocalDateTime ldt2 = java.time.LocalDateTime.parse(dt2, formatter);
            java.time.Duration duration = java.time.Duration.between(ldt1, ldt2);
            return Math.abs(duration.toHours());
        } catch (Exception e) {
            return 0;
        }
    }
}
