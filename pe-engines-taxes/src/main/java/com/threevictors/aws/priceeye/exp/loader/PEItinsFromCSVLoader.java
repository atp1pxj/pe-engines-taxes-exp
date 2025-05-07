package com.threevictors.aws.priceeye.exp.loader;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class PEItinsFromCSVLoader {

        public static void main(String[] args) {
            String templateFilePath = "pe-engines-taxes/src/main/resources/PEItineraries_from_athena_csv/PEItin_template.txt";
            String csvFilePath = "pe-engines-taxes/src/main/resources/PEItineraries_from_athena_csv/peitn_first_5_template_data.csv";
            String outputFilePath = "pe-engines-taxes/src/main/resources/PEItineraries_from_athena_csv/PEItins_from_athena.txt";

            try {
                // Read the template
                String template = Files.readString(Paths.get(templateFilePath));

                // Read the CSV file
                List<Map<String, String>> csvData = readCsv(csvFilePath);

                // Generate the output file
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath))) {
                    for (Map<String, String> row : csvData) {
                        String filledTemplate = fillTemplate(template, row);
                        writer.write(filledTemplate);
                        writer.newLine();
                    }
                }

                System.out.println("File generated successfully: " + outputFilePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private static List<Map<String, String>> readCsv(String filePath) throws IOException {
            List<Map<String, String>> data = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String[] headers = reader.readLine().split(",");
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] values = line.split(",");
                    Map<String, String> row = new HashMap<>();
                    for (int i = 0; i < headers.length; i++) {
                        row.put(headers[i], values[i]);
                    }
                    data.add(row);
                }
            }
            return data;
        }

        private static String fillTemplate(String template, Map<String, String> row) {
            String result = template;
            for (Map.Entry<String, String> entry : row.entrySet()) {
                result = result.replace("${" + entry.getKey() + "}", entry.getValue());
            }
            return result;
        }

}
