package com.threevictors.aws.priceeye.exp.loader;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public class PFCAmountsLoader {

    public static Map<String, Double> parseAndLoadPFCTaxesRedisData(String filePath) {
        Map<String, Double> keyAmountMap = new LinkedHashMap<>();
        DecimalFormat df = new DecimalFormat("0.00");

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;

            while ((line = reader.readLine()) != null) {
                String key = extractBetween(line, "Key: ", ", Value:");
                String amountStr = extractBetween(line, "amount=", ",");

                if (key != null && amountStr != null) {
                    double amount = Double.parseDouble(df.format(Double.parseDouble(amountStr)));
                    keyAmountMap.put(key, amount);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return keyAmountMap;
    }

    private static String extractBetween(String text, String start, String end) {
        int startIdx = text.indexOf(start);
        if (startIdx == -1) return null;
        startIdx += start.length();
        int endIdx = text.indexOf(end, startIdx);
        if (endIdx == -1) return null;
        return text.substring(startIdx, endIdx).trim();
    }

    // Example usage
    public static void main(String[] args) {
        //"pe-engines-taxes/src/main/resources/xldatapoints_all_taxrecs_from_redis_all_20250515.txt"
        Map<String, Double> result = parseAndLoadPFCTaxesRedisData("pe-engines-taxes/src/main/resources/pfcRedisDump_20250520.txt");
        result.forEach((k, v) -> System.out.println("Key: " + k + ", amount=" + String.format("%.2f", v)));
    }
}
