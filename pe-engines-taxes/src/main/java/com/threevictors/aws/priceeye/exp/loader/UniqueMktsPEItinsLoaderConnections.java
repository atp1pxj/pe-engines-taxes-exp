package com.threevictors.aws.priceeye.exp.loader;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import com.threevictors.aws.priceeye.exp.dao.MetadataReader;
import lombok.Data;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

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

        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>( 1_000_000 );

        ThreadPoolExecutor executor = new ThreadPoolExecutor( Runtime.getRuntime().availableProcessors() * 2, Runtime.getRuntime().availableProcessors() * 2, 10, TimeUnit.SECONDS, workQueue,
                new BlockingRejectedExecutionHandler());

        CSVParser parser = new CSVParserBuilder()
                .withSeparator(',')
                .build();

        try (
            CSVReader reader = new CSVReaderBuilder( new FileReader(inputFile) ).withCSVParser(parser).build();
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))
        ) {
            String[] header = reader.readNext();

            if (header != null) {
                writer.write(String.join(",", header));
                writer.newLine();
            }

            Map<String, Integer> headerMap = createHeaderIndexMap(header);

            String[] values;
            final AtomicLong count = new AtomicLong(0);

            while ((values = reader.readNext()) != null) {
                executor.execute(new ParseRunnable(headerMap, values, writer, count) );
            }

            executor.shutdown();
            try {
                executor.awaitTermination(60, TimeUnit.MINUTES);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
            writer.flush();
        } catch (IOException e) {
            System.err.println("Error during processing: " + e.getMessage());
            System.exit(1);
        } catch (CsvValidationException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Done. Output written to " + outputFile.getAbsolutePath());
    }

    static class ParseRunnable implements Runnable {

        Map<String, Integer> headerMap;
        String [] values;
        BufferedWriter writer;
        AtomicLong count;

        public ParseRunnable(Map<String, Integer> headerMap, String[] values, BufferedWriter writer, AtomicLong count) {
            this.headerMap = headerMap;
            this.values = values;
            this.writer = writer;
            this.count = count;
        }

        public void run() {
            Leg obl1Leg = buildLeg(values, headerMap, true, 1);
            Leg obl2Leg = buildLeg(values, headerMap, true, 2);

            Leg ibl1Leg = buildLeg(values, headerMap, false, 1);
            Leg ibl2Leg = buildLeg(values, headerMap, false, 2);

            String key = generateKey(obl1Leg, obl2Leg, ibl1Leg, ibl2Leg);


            if (key != null && seenKeys.add(key)) {
                try {
                    writer.write(String.join(",", values) + '\n');
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }

            long actualCount = count.incrementAndGet();
            if (actualCount % 1_000_000 == 0) {
                System.out.println("Processed lines: " + count);
            }
        }
    }

        private static Leg buildLeg(String [] values, Map<String, Integer> headerMap, boolean isOutboundLeg, int legNumber) {

        String prefix = (isOutboundLeg ? "OBL" : "IBL") + legNumber + "_";

        String obl1MktCarrier = values[headerMap.get(prefix + "mkt_carrier")].trim();

        String obl1Orig    = values[headerMap.get( prefix + "originairportcode")].trim();
        String obl1Dest    = values[headerMap.get( prefix + "destinationairportcode")].trim();
        String obl1DepDate = values[headerMap.get( prefix + "departdate")].trim();
        String obl1DepTime = values[headerMap.get( prefix + "departtime")].trim();
        String obl1ArrDate = values[headerMap.get( prefix + "arrivedate")].trim();
        String obl1ArrTime = values[headerMap.get( prefix + "arrivetime")].trim();

        Leg leg = new Leg();
        leg.setMktCarrier(obl1MktCarrier);
        leg.setOriginAirportCode(obl1Orig);
        leg.setDestinationAirportCode(obl1Dest);
        leg.setDepartDate( obl1DepDate );
        leg.setDepartTime( obl1DepTime );
        leg.setArriveDate( obl1ArrDate );
        leg.setArriveTime( obl1ArrTime );

        return leg;
    }
    private static Map<String, Integer> createHeaderIndexMap(String [] headerLine) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headerLine.length; i++) {
            map.put(headerLine[i].trim(), i);
        }
        return map;
    }

    private static String generateKey(Leg obl1Leg, Leg obl2Leg, Leg ibl1Leg, Leg ibl2Leg ) {

        try {

            // Is the outbound connecting airport in the US
            if (isUSAirport( obl1Leg.getDestinationAirportCode() )) {
                if (hoursBetween(obl1Leg, obl2Leg) > 12) {
                    return null;
                }
            }

            // Is the inbound connecting airport in the US
            if (isUSAirport( ibl1Leg.getDestinationAirportCode() )) {
                if (hoursBetween(ibl1Leg, ibl2Leg) > 12) {
                    return null;
                }
            }

            // --- End 12 hour stopover logic ---

            // Check for multi carrier
            Set<String> mktCarriers = new HashSet<>();
            mktCarriers.add(obl1Leg.getMktCarrier());
            mktCarriers.add(obl2Leg.getMktCarrier());
            mktCarriers.add(ibl1Leg.getMktCarrier());
            mktCarriers.add(ibl2Leg.getMktCarrier());

            if (mktCarriers.size() > 1) {
                return null;
            }

            // - End MultiCarrier

            // Toss toothy grins. These are like OpenJaws, in that we don't return to the same airport. However, the airport is in the same city
            // Seems to cause problems with PFC calculations
            String obl1Orig = obl1Leg.getOriginAirportCode();
            String ibl2Dest = ibl2Leg.getDestinationAirportCode();

            if (!obl1Orig.equalsIgnoreCase(ibl2Dest)) {
                return null;
            }

            return String.join("-",
                        obl1Leg.getMktCarrier(), // They are all the same after the multi carrier filter above

                        obl1Leg.getOriginAirportCode(),
                        obl1Leg.getDestinationAirportCode(),

                        obl2Leg.getOriginAirportCode(),
                        obl2Leg.getDestinationAirportCode(),

                        ibl1Leg.getOriginAirportCode(),
                        ibl1Leg.getDestinationAirportCode(),

                        ibl2Leg.getOriginAirportCode(),
                        ibl2Leg.getDestinationAirportCode()
                );
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
    private static long hoursBetween(Leg leg1, Leg leg2) {
        try {
            String dt1 = leg1.getDepartDate() + " " + String.format("%04d", Integer.parseInt(leg1.getArriveTime()));
            String dt2 = leg2.getArriveDate() + " " + String.format("%04d", Integer.parseInt(leg2.getDepartTime()));

            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd HHmm");
            java.time.LocalDateTime ldt1 = java.time.LocalDateTime.parse(dt1, formatter);
            java.time.LocalDateTime ldt2 = java.time.LocalDateTime.parse(dt2, formatter);
            // Calculate the absolute duration between the two times
            java.time.Duration duration = java.time.Duration.between(ldt1, ldt2);
            if (duration.isNegative()) {
                duration = duration.negated();
            }
            return duration.toHours();
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }


    // Custom handler that blocks the submitter when the queue is full
    static class BlockingRejectedExecutionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            try {
                // Block until space becomes available in the queue
                executor.getQueue().put(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Task submission interrupted", e);
            }
        }
    }

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
