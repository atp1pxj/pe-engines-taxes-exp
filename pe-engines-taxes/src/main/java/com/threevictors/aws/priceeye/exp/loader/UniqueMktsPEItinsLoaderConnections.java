package com.threevictors.aws.priceeye.exp.loader;

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
    private static final List<String> outputLines = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws IOException {

        //Note: Very important - Make sure the headers ARE NOT WRAPPED IN QUOTES in the input CSV file. Otherwise, the logic will fail to work correctly.
        // Check if both input and output file paths are provided
        if (args.length < 2) {
            System.err.println("Error: Please provide both input and output file paths as arguments.");
            System.exit(1);
        }

        String inputFilePath = args[0];
        String outputFilePath = args[1];

        File inputFile = new File(inputFilePath);
        File outputFile = new File(outputFilePath);

        processFile(inputFile);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            for (String line : outputLines) {
                writer.write(line);
                writer.newLine();
            }
        }

        //Note: Once the output file is generated, delete the header row from the output file manually as it will fail the parsing logic.
        System.out.println("Done. Output written to " + outputFile.getAbsolutePath());
    }

    private static void processFile(File inputFile) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
            String header = reader.readLine();
            if (header != null) {
                outputLines.add(header);
            }

            Map<String, Integer> headerMap = createHeaderIndexMap(header);
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",", -1);

                String key = generateKey(values, headerMap);
                if (key != null && seenKeys.add(key)) {
                    outputLines.add(line);
                }
            }
        }
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
        try {
            return String.join("-",
                    values[headerMap.get("itin_validatingcarrier")].trim(),

                    values[headerMap.get("OBL1_originairportcode")].trim(),
                    values[headerMap.get("OBL1_destinationairportcode")].trim(),
                    values[headerMap.get("OBL1_mkt_carrier")].trim(),

                    values[headerMap.get("OBL2_originairportcode")].trim(),
                    values[headerMap.get("OBL2_destinationairportcode")].trim(),
                    values[headerMap.get("OBL2_mkt_carrier")].trim(),

                    values[headerMap.get("IBL1_originairportcode")].trim(),
                    values[headerMap.get("IBL1_destinationairportcode")].trim(),
                    values[headerMap.get("IBL1_mkt_carrier")].trim(),

                    values[headerMap.get("IBL2_originairportcode")].trim(),
                    values[headerMap.get("IBL2_destinationairportcode")].trim(),
                    values[headerMap.get("IBL2_mkt_carrier")].trim()

            );
        } catch (Exception e) {
            return null;
        }
    }
}
