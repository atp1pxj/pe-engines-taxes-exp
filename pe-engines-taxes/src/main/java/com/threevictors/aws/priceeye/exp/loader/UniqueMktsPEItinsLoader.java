package com.threevictors.aws.priceeye.exp.loader;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;


public class UniqueMktsPEItinsLoader {
    private static final Set<String> seenKeys = ConcurrentHashMap.newKeySet();
    private static final List<String> outputLines = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws IOException, InterruptedException {
    //public static void populateItinDataFromAthenaCSVParallel() throws IOException {

        //All parts
        // /Users/pjannapureddy/All_sprints_2/3victors/2025_sprints/Sprint8_Apr14_Apr_25/1_Athena_query_results/large_input_file_and_split/split_output/

        File inputDir = new File("/Users/pjannapureddy/All_sprints_2/3victors/2025_sprints/Sprint8_Apr14_Apr_25/1_Athena_query_results/large_input_file_and_split/split_output/");
        File outputFile = new File("pe-engines-taxes/src/main/resources/PEItineraries_from_athena_csv_parallel/generated_output_txts/PEItins_parallel_unique_output.txt");
        String template = new String(Files.readAllBytes(Paths.get("pe-engines-taxes/src/main/resources/PEItineraries_from_athena_csv_parallel/PEItin_template.txt")));

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Future<?>> futures = new ArrayList<>();

        for (File file : Objects.requireNonNull(inputDir.listFiles((dir, name) -> name.endsWith(".csv")))) {
            futures.add(executor.submit(() -> {
                try {
                    processFile(file, template);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }));
        }


        for (Future<?> future : futures) {
            try {
                future.get(); // wait for all to finish
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace(); // handle exceptions appropriately
            }
        }
        executor.shutdown();

        // Write all results to one output file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            for (String line : outputLines) {
                writer.write(line);
                writer.newLine();
            }
        }

        System.out.println("Done. Output written to " + outputFile.getAbsolutePath());
    }

    private static void processFile(File inputFile, String template) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return;
            }

            List<String> headers = Arrays.asList(headerLine.split(","));
            Map<String, Integer> headerMap = new HashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                headerMap.put(headers.get(i).trim(), i);
            }

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
