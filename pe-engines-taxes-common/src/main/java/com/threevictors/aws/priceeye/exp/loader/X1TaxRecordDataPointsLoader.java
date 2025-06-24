package com.threevictors.aws.priceeye.exp.loader;

import com.threevictors.aws.priceeye.exp.model.taxengine.response.X1TaxRecordDataPoints;
import lombok.Data;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

@Data
public class X1TaxRecordDataPointsLoader {


    public X1TaxRecordDataPointsLoader() {}

    //Method to load tax record data points by reading the contents of the file and adding them to a hashmap with key as the key
    //and value as the X1TaxRecordDataPoints object
    public Map<String, X1TaxRecordDataPoints> loadTaxRecordDataPoints(String filePath) {
        Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Remove outer curly braces
                line = line.substring(1, line.length() - 1);

                X1TaxRecordDataPoints dataPoint = new X1TaxRecordDataPoints();

                // Split by comma but not within single quotes
                String[] fields = line.split(",(?=(?:[^']*'[^']*')*[^']*$)");

                for (String field : fields) {
                    String[] keyValue = field.trim().split("=", 2);
                    if (keyValue.length == 2) {
                        String key = keyValue[0].trim();
                        String value = keyValue[1].replaceAll("'", "").trim();

                        switch (key) {
                            case "key":
                                // Extract only the part before the dot
                                int dotIndex = value.indexOf('.');
                                value = dotIndex != -1 ? value.substring(0, dotIndex) : value;
                                dataPoint.setKey(value);
                                break;
                            case "nation":
                                dataPoint.setNation(value);
                                break;
                            case "taxCode":
                                dataPoint.setTaxCode(value);
                                break;
                            case "percentOrFlatTag":
                                dataPoint.setPercentOrFlatTag(value);
                                break;
                            case "seqNo":
                                dataPoint.setSeqNo(Integer.parseInt(value));
                                break;
                            case "taxCarrier":
                                dataPoint.setTaxCarrier(value);
                                break;
                            case "taxAmount":
                                dataPoint.setTaxAmount("null".equals(value) ? null : Double.parseDouble(value));
                                break;
                            case "taxAmountCurrency":
                                dataPoint.setTaxAmountCurrency("null".equals(value) ? null : value);
                                break;
                            case "taxPercent":
                                dataPoint.setTaxPercent(Double.parseDouble(value));
                                break;
                            case "minTaxWhenPercent":
                                dataPoint.setMinTaxWhenPercent(Double.parseDouble(value));
                                break;
                            case "maxTaxWhenPercent":
                                dataPoint.setMaxTaxWhenPercent(Double.parseDouble(value));
                                break;
                        }
                    }
                }

                if (dataPoint.getKey() != null) {
                    x1TaxRecordDataPointsMap.put(dataPoint.getKey(), dataPoint);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading file: " + filePath, e);
        }
        return x1TaxRecordDataPointsMap;
    }

    /**
     * Overloaded method to load tax record data points from an InputStream. Better for executing from a JAR file.
     * @param in
     * @return
     */
    public Map<String, X1TaxRecordDataPoints> loadTaxRecordDataPoints(InputStream in) {
        Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Remove outer curly braces
                line = line.substring(1, line.length() - 1);

                X1TaxRecordDataPoints dataPoint = new X1TaxRecordDataPoints();

                // Split by comma but not within single quotes
                String[] fields = line.split(",(?=(?:[^']*'[^']*')*[^']*$)");

                for (String field : fields) {
                    String[] keyValue = field.trim().split("=", 2);
                    if (keyValue.length == 2) {
                        String key = keyValue[0].trim();
                        String value = keyValue[1].replaceAll("'", "").trim();

                        switch (key) {
                            case "key":
                                // Extract only the part before the dot
                                int dotIndex = value.indexOf('.');
                                value = dotIndex != -1 ? value.substring(0, dotIndex) : value;
                                dataPoint.setKey(value);
                                break;
                            case "nation":
                                dataPoint.setNation(value);
                                break;
                            case "taxCode":
                                dataPoint.setTaxCode(value);
                                break;
                            case "percentOrFlatTag":
                                dataPoint.setPercentOrFlatTag(value);
                                break;
                            case "seqNo":
                                dataPoint.setSeqNo(Integer.parseInt(value));
                                break;
                            case "taxCarrier":
                                dataPoint.setTaxCarrier(value);
                                break;
                            case "taxAmount":
                                dataPoint.setTaxAmount("null".equals(value) ? null : Double.parseDouble(value));
                                break;
                            case "taxAmountCurrency":
                                dataPoint.setTaxAmountCurrency("null".equals(value) ? null : value);
                                break;
                            case "taxPercent":
                                dataPoint.setTaxPercent(Double.parseDouble(value));
                                break;
                            case "minTaxWhenPercent":
                                dataPoint.setMinTaxWhenPercent(Double.parseDouble(value));
                                break;
                            case "maxTaxWhenPercent":
                                dataPoint.setMaxTaxWhenPercent(Double.parseDouble(value));
                                break;
                        }
                    }
                }

                if (dataPoint.getKey() != null) {
                    x1TaxRecordDataPointsMap.put(dataPoint.getKey(), dataPoint);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading resource: ", e);
        }
        return x1TaxRecordDataPointsMap;
    }
}