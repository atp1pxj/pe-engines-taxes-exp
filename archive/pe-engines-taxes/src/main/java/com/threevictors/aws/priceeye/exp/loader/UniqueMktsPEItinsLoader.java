package com.threevictors.aws.priceeye.taxes.loader;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;


//NEW INPUT CSV DATA GENERATION CLASS
//NOTE: Run this class to generate the unique itineraries from the large input files.
// The output will be written to a text file in the specified directory.
public class UniqueMktsPEItinsLoader {
    private static final Set<String> seenKeys = ConcurrentHashMap.newKeySet();
    private static final List<String> outputLines = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws IOException, InterruptedException {
    //public static void populateItinDataFromAthenaCSVParallel() throws IOException {

        //All parts
        // /Users/pjannapureddy/All_sprints_2/3victors/2025_sprints/Sprint8_Apr14_Apr_25/1_Athena_query_results/large_input_file_and_split/split_output/

        File outputFile = new File("pe-engines-taxes/src/main/resources/PEItineraries_from_athena_csv_parallel/generated_output_txts/PEItins_parallel_unique_output.txt");
        String template = new String(Files.readAllBytes(Paths.get("pe-engines-taxes/src/main/resources/PEItineraries_from_athena_csv_parallel/PEItin_template.txt")));

        //processFile(new File("pe-engines-taxes/src/main/data/raw-us-world.csv"), template);
        processFile(new File("/Users/pjannapureddy/All_sprints_2/3victors/2025_sprints/Sprint8_Apr14_Apr_25_taxes/1_Athena_query_results/1_athena_data_big_250622.csv"));

        // Write all results to one output file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            for (String line : outputLines) {
                writer.write(line);
                writer.newLine();
            }
        }

        System.out.println("Done. Output written to " + outputFile.getAbsolutePath());
    }

    //private static void processFile(File inputFile, String template) throws IOException {
    private static void processFile(File inputFile) throws IOException {

        //Note: Very important - Make sure the headers ARE NOT WRAPPED IN QUOTES in the input CSV file. Otherwise, the logic will fail to work correctly.
        String headerLine = "itin_validatingcarrier,OBL_originairportcode,OBL_destinationairportcode,OBL_flightNumber,OBL_departdate,OBL_departtime,OBL_arrivedate,OBL_arrivetime,IBL_originairportcode,IBL_destinationairportcode,IBL_flightnumber,IBL_departdate,IBL_departtime,IBL_arrivedate,IBL_arrivetime,itin_cabin,itin_bookingcode,itin_totalamount,taxbreakdown,yq_val,yr_val,itin_true_tax_amount";

        List<String> headers = Arrays.asList(headerLine.split(","));
        Map<String, Integer> headerMap = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            headerMap.put(headers.get(i).trim(), i);
        }


        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",");
                String key = generateKey(values, headerMap);

                if (key == null || !seenKeys.add(key)) {
                    continue; // skip duplicate key
                }
                //String filled = fillTemplate(template, values, headerMap);
                //outputLines.add(filled);
                outputLines.add(line);
            }
        }
    }

    private static String generateKey(String[] values, Map<String, Integer> headerMap) {
        try {
            return String.join("-",
                    values[headerMap.get("itin_validatingcarrier")].trim(),
                    values[headerMap.get("OBL_originairportcode")].trim(),
                    values[headerMap.get("OBL_destinationairportcode")].trim(),
                    values[headerMap.get("IBL_originairportcode")].trim(),
                    values[headerMap.get("IBL_destinationairportcode")].trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String fillTemplate(String template, String[] values, Map<String, Integer> headerMap) {
        String result = template;
        for (Map.Entry<String, Integer> entry : headerMap.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            String value = entry.getValue() < values.length ? values[entry.getValue()].trim() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

}
