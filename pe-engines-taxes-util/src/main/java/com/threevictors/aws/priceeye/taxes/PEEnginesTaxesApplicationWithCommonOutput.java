package com.threevictors.aws.priceeye.taxes;

import com.threevictors.aws.configreader.configuration.reader.heavy.ConfigurationReader;
import com.threevictors.aws.data.aws.RawLeg;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.taxes.data.TaxLadder;
import com.threevictors.aws.priceeye.taxes.loader.CommonOutputPEItinsLoader;
import com.threevictors.aws.priceeye.taxes.model.taxengine.response.X1TaxRecordDataPoints;
import com.threevictors.aws.priceeye.taxes.loader.X1TaxRecordDataPointsLoader;
import com.threevictors.aws.priceeye.taxes.processor.PEItineraryTaxProcessor;
import com.threevictors.aws.priceeye.taxes.taxengine.SharedHttpFactory;
import com.threevictors.common.aws.s3.S3Util;
import com.threevictors.common.dates.IntegerDate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class loads PEItineraries from CommonOutput data and processes them in parallel
 * using the tax engine.
 */
public class PEEnginesTaxesApplicationWithCommonOutput {

    private static final Logger log = LogManager.getLogger(PEEnginesTaxesApplicationWithCommonOutput.class);

    //private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 8; // Doubled thread count to handle I/O-bound operations

    //Take totalThreadCount from runtime arg
    private final int totalThreadCount;
    private final ExecutorService executor;

    private final PEItineraryTaxProcessor peItineraryTaxProcessor;
    private final CommonOutputPEItinsLoader commonOutputPEItinsLoader;

    private final S3Util s3Util;

    public PEEnginesTaxesApplicationWithCommonOutput(int totalThreadCount) throws Exception {
        // Store the thread count
        this.totalThreadCount = totalThreadCount;

        // Create a thread pool with the specified number of threads
        //executor = Executors.newFixedThreadPool(THREAD_COUNT);
        executor = Executors.newFixedThreadPool(totalThreadCount);

        SharedHttpFactory.setupInstance( executor, totalThreadCount);

        commonOutputPEItinsLoader = new CommonOutputPEItinsLoader();

        s3Util = new S3Util();
        Properties p = ConfigurationReader.readProperties("pe-engines-taxes.properties");
        String fileBucket = p.getProperty("x1taxrecords.file.bucket").trim();
        String bucketObjectKey = p.getProperty("x1taxrecords.file.object.name").trim();

        Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap = getX1TaxRecordDataPointsMap(fileBucket, bucketObjectKey);

        // Initialize the PEItineraryProcessor with the required dependencies
        peItineraryTaxProcessor = new PEItineraryTaxProcessor( x1TaxRecordDataPointsMap );
    }



    private Map<String, X1TaxRecordDataPoints> getX1TaxRecordDataPointsMap( String fileBucket, String bucketObjectKey ) throws Exception {
        // Check if object exists
        if (s3Util.doesObjectExist(fileBucket, bucketObjectKey)) {
            log.info("Loading X1 tax record data points from engine redis dump file...");
            InputStream inputStream = s3Util.getFileInputStream(fileBucket, bucketObjectKey);

            if (inputStream == null) {
                log.error("X1 tax record data points file could be empty in S3 bucket: {} with key: {}", fileBucket, bucketObjectKey);
                throw new FileNotFoundException("X1 tax datapoints file could be empty or not present - " + bucketObjectKey);
            }

            return Collections.unmodifiableMap( X1TaxRecordDataPointsLoader.loadTaxRecordDataPoints( inputStream ) );
        }
        else {
            log.error("X1 tax record data points file not found in S3 bucket: {} with key: {}", fileBucket, bucketObjectKey);
            throw new FileNotFoundException(" X1 tax datapoints file not found - " + bucketObjectKey);
        }
    }


    private void processItinerariesStreaming(File tmpFile, int salesDate, String customer, String pos, String schemaSuffix, int limit) {
        try (PrintWriter pWriter = new PrintWriter(tmpFile)) {
            log.info("Starting streaming processing of itineraries");

            //TODO: Check if this is still supposed to be totalThreadCount * 8 or just totalThreadCount.
            // Create a bounded semaphore to limit the number of concurrent tasks
            Semaphore semaphore = new Semaphore(8192 );

            // Keep track of active futures for proper cleanup
            List<CompletableFuture<Void>> activeFutures = new ArrayList<>();
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger submittedCount = new AtomicInteger(0);

            // Stream and process itineraries
            commonOutputPEItinsLoader.streamPEItins(salesDate, customer, pos, schemaSuffix, limit,
                    itineraryPair -> {
                        try {
                            // Acquire a permit from the semaphore before submitting a new task
                            semaphore.acquire();

                            int currentCount = submittedCount.incrementAndGet();
                            if (currentCount % 1000 == 0) {
                                log.info("Submitted {} itineraries for processing", currentCount);
                            }

                            // Process the itinerary
                            CompletableFuture<TaxLadder> taxLadder = peItineraryTaxProcessor.processPEItinerary(itineraryPair.getX(), itineraryPair.getY(), salesDate);

                            CompletableFuture<Void> future = taxLadder.thenAcceptAsync( ladder -> {
                                 writeTaxComparison(pWriter, itineraryPair.getX(), itineraryPair.getY(), ladder);
                                 // Release the permit back to the semaphore when the task is done
                                 semaphore.release();
                                 int processed = processedCount.incrementAndGet();
                                 if (processed % 1000 == 0) {
                                     log.info("Processed {} itineraries", processed);
                                 }
                             }, executor)
                             .exceptionally( x -> {
                                 semaphore.release();
                                 return null;
                             });


                            // Add to active futures and clean up completed ones periodically
                            synchronized (activeFutures) {
                                activeFutures.add(future);

                                // Periodically clean up completed futures to prevent memory buildup
                                if (activeFutures.size() % 1000 == 0) {
                                    activeFutures.removeIf(CompletableFuture::isDone);
                                }
                            }

                        } catch (InterruptedException e) {
                            log.error("Error acquiring semaphore", e);
                            Thread.currentThread().interrupt();
                        }
                    });

            // Wait for all remaining futures to complete
            log.info("Waiting for all processing to complete...");

            CompletableFuture.allOf(activeFutures.toArray(new CompletableFuture[0])).join();

            log.info("All itinerary processing completed. Processed: {}, Submitted: {}",
                            processedCount.get(), submittedCount.get());

        } catch (Exception e) {
            log.error("Error in streaming processing", e);
        }

        log.info("Exiting processItinerariesStreaming.");
    }

    /**
     * Shutdown the executor service
     */
    private void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                    log.warn("Executor did not terminate in the specified time.");
                }
            } catch (InterruptedException e) {
                log.error("Error shutting down executor", e);
            }
        }
    }


    private String buildLeg(RawLeg leg) {
        String format = "%s,%s,%s,%04d,%d,%d,%d,%d,%s,%s";
        String empty  = ",,,,,,,,,";

        if (leg == null) return empty;

        return String.format(format, leg.getOriginAirportCode(), leg.getDestinationAirportCode(), leg.getMarketingCarrier(), leg.getFlightNumber(), leg.getDepartDate(), leg.getDepartTime(), leg.getArriveDate(), leg.getArriveTime(),
                leg.getFareClass(), leg.getCabin());
    }

    private void writeTaxComparison(PrintWriter pWriter, String pointOfSale, PEItinerary currentItin, TaxLadder taxLadder) {
        // Create a StringBuilder to build the CSV line
        StringBuilder csvLine = new StringBuilder();

        String originAirportCode = currentItin.getOutboundLegs().get(0).getOriginAirportCode();
        String destinationAirportCode = currentItin.getOutboundLegs().get(currentItin.getOutboundLegs().size() - 1).getDestinationAirportCode();
        int departureDate = currentItin.getOutboundLegs().get(0).getDepartDate();
        int returnDate = ( currentItin.getInboundLegs() == null || currentItin.getInboundLegs().isEmpty()) ? 0 : currentItin.getInboundLegs().get(0).getDepartDate();
        int stops = (returnDate==0) ? currentItin.getOutboundLegs().size()-1 : Math.max(currentItin.getOutboundLegs().size() - 1, currentItin.getInboundLegs().size()-1);

        String outboundLeg1 = buildLeg(currentItin.getOutboundLegs().get(0));
        String outboundLeg2 = (currentItin.getOutboundLegs().size() == 1) ? buildLeg( null ) : buildLeg(currentItin.getOutboundLegs().get(1));
        String inboundLeg1 = (returnDate == 0) ? buildLeg( null ) : buildLeg(currentItin.getInboundLegs().get(0));
        String inboundLeg2 = (returnDate != 0 && currentItin.getInboundLegs().size() > 1) ? buildLeg(currentItin.getInboundLegs().get(1)) : buildLeg( null );

        csvLine.append(pointOfSale).append(",");
        csvLine.append(originAirportCode).append(",");
        csvLine.append(destinationAirportCode).append(",");
        csvLine.append(departureDate).append(",");
        csvLine.append(returnDate).append(",");
        csvLine.append(stops).append(",");
        csvLine.append(outboundLeg1).append(",");
        csvLine.append(outboundLeg2).append(",");
        csvLine.append(inboundLeg1).append(",");
        csvLine.append(inboundLeg2).append(",");

        // Add total price and channel
        csvLine.append(currentItin.getCurrency()).append(",");
        csvLine.append(String.format("%.2f,", currentItin.getTotalPrice()));
        csvLine.append(String.format("%.2f,", currentItin.getYqyr()));
        csvLine.append(String.format("%.2f,", currentItin.getTaxes() - currentItin.getYqyr()));
        csvLine.append(String.format("%.2f,", taxLadder.getTotalFlatTaxRate().doubleValue() + taxLadder.getTotalPercentageTaxRate().doubleValue()));
        csvLine.append(currentItin.getChannel()).append(",");

        // Add current timestamp
        LocalDateTime now = LocalDateTime.ofEpochSecond( currentItin.getObservationTimestamp() / 1000, 0, ZoneOffset.UTC);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        csvLine.append(now.format(formatter));

        synchronized (pWriter) {
            pWriter.println( csvLine );
        }
    }


    private void compareTaxLadders(PEItinerary currentItin, TaxLadder taxLadder) {
        if (currentItin.getTaxLadder() == null || currentItin.getTaxLadder().isEmpty()) {
            log.error("currentItin.getTaxLadder() is null or empty. Cannot compare!");
            return;
        }

        Map<String, String> currentItinTaxLadderMap = new TreeMap<>();
        for (String taxCodeText : currentItin.getTaxLadder()) {
            String[] parts = taxCodeText.split(" ");
            if (parts.length == 2) {
                currentItinTaxLadderMap.put(parts[0], parts[1]);
            }
        }
        //remove YQ and YR entries from the map.
        currentItinTaxLadderMap.remove("YQ");
        currentItinTaxLadderMap.remove("YR");

        Set<String> taxKeys = new TreeSet<>(currentItinTaxLadderMap.keySet());
        taxKeys.addAll(taxLadder.getFlatTaxCodes());
        taxKeys.addAll(taxLadder.getPercentageTaxCodes());

        boolean mismatchFound = false;
        for (String taxKey : taxKeys) {
            String expectedValue = currentItinTaxLadderMap.get(taxKey);
            BigDecimal responseValue = taxLadder.getTaxRate(taxKey);

            if (expectedValue == null || responseValue == null || Double.parseDouble(expectedValue) != responseValue.doubleValue()) {
                String diff = "";
                if (expectedValue != null && responseValue != null) {
                    diff = String.format("%.2f", Double.parseDouble(expectedValue) - responseValue.doubleValue());
                }
                log.error(String.format("%s: Expected: %6s Actual: %6s Diff: %6s LN: %s", taxKey, expectedValue == null ? "------" : expectedValue, responseValue == null ? "------" : responseValue, diff, currentItin.getChannel()));
                mismatchFound = true;
            }
        }

        if (mismatchFound) {
            log.info("\n");
        }
    }


    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Mandatory arguments - 'salesDate' with Format: yyyyMMdd"
                    + " and 'customer' need to be provided.\n"
                    + "Optional arguments in order: pos, totalThreadCount, limit, schemaSuffix");
        }

        String salesDateStr = args[0];
        if (!salesDateStr.matches("\\d{8}")) {
            throw new IllegalArgumentException("Invalid 'salesDate' format. Expected format: yyyyMMdd");
        }
        int salesDate = Integer.parseInt(salesDateStr);

        String customer = args[1];

        // Optional arguments
        String pos = "*";
        int totalThreadCount = Runtime.getRuntime().availableProcessors() * 8;
        int limit = 100;
        String schemaSuffix = "";

        if (args.length > 2) {
            pos = args[2];
        }

        if (args.length > 3) {
            try {
                totalThreadCount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                log.warn("Invalid 'totalThreadCount'. Using default value: Runtime.getRuntime().availableProcessors() * 8");
            }
        }

        if (args.length > 4) {
            try {
                limit = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                log.warn("Invalid 'limit' format. Using default value: 100");
            }
        }

        if (args.length > 5) {
            schemaSuffix = (args[5] != null) ? args[5].trim() : "";
        }

        // Log the arguments for verification
        log.info("Sales Date: {}", salesDate);
        log.info("Customer: {}", customer);
        log.info("POS: {}", pos);
        log.info("totalThreadCount: {}", totalThreadCount);
        log.info("Limit: {}", limit);
        log.info("Schema Suffix: {}", schemaSuffix);

        Properties p = ConfigurationReader.readProperties("pe-engines-taxes.properties");
        //Bucket name
        String mismatchFileBucket = p.getProperty("commonoutput.peitins.taxmismatch.file.bucket").trim();

        File logFile = File.createTempFile("pe-engines-taxes", ".log");
        PEEnginesTaxesApplicationWithCommonOutput currentApp = new PEEnginesTaxesApplicationWithCommonOutput(totalThreadCount);

        try {

            //starttime
            LocalDateTime startTime = LocalDateTime.now();
            log.info("Starting processing at {}", startTime);
            currentApp.processItinerariesStreaming(logFile, salesDate, customer, pos, schemaSuffix, limit);

            S3Util s3Util = new S3Util();
            String remoteFileName = String.format("%d/%02d/%02d/%s", IntegerDate.getYear(salesDate), IntegerDate.getMonth(salesDate), IntegerDate.getDay(salesDate), UUID.randomUUID());
            s3Util.uploadObject(logFile, mismatchFileBucket, remoteFileName);

            log.info("Returned to main.{}", logFile.getAbsolutePath());
            log.info("✅ Processing complete.");

            //endtime
            LocalDateTime endTime = LocalDateTime.now();
            log.info("Ended processing at {}", endTime);

            //totaltime Taken
            double seconds = Duration.parse(Duration.between(startTime, endTime).toString()).toMillis()/1000.0;
            log.info("Total time taken to run the app: {} seconds", String.format("%.2f", seconds));

        } catch (Exception e) {
            log.error("Error processing itineraries", e);
        }
        finally {
            // Ensure resources are cleaned up
            currentApp.shutdown();
        }
    }
}
