package com.threevictors.aws.priceeye.exp;

import com.threevictors.aws.configreader.configuration.reader.heavy.ConfigurationReader;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.exp.loader.CommonOutputPEItinsLoader;
import com.threevictors.aws.priceeye.exp.model.taxengine.response.X1TaxRecordDataPoints;
import com.threevictors.aws.priceeye.exp.taxengine.PFCTaxEngineCommunicator;
import com.threevictors.aws.priceeye.exp.taxengine.TaxEngineCommunicator;
import com.threevictors.aws.priceeye.exp.loader.X1TaxRecordDataPointsLoader;
import com.threevictors.common.aws.s3.S3Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * This class loads PEItineraries from CommonOutput data and processes them in parallel
 * using the tax engine.
 */
public class PEEnginesTaxesApplicationWithCommonOutput {

    private static final Logger log = LogManager.getLogger(PEEnginesTaxesApplicationWithCommonOutput.class);

    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 2; // Doubled thread count to handle I/O-bound operations

    private static final String QUERY_ID_PREFIX_3V = "3v-";

    private Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap;

    private ExecutorService executor;

    private TaxEngineCommunicator taxEngineCommunicator;
    private PFCTaxEngineCommunicator pfcTaxEngineCommunicator;
    private PEItineraryTaxProcessor peItineraryTaxProcessor;
    private CommonOutputPEItinsLoader commonOutputPEItinsLoader;

    private S3Util s3Util;

    public PEEnginesTaxesApplicationWithCommonOutput() throws Exception {
        // Create a thread pool with the specified number of threads
        executor = Executors.newFixedThreadPool(THREAD_COUNT);

        taxEngineCommunicator = new TaxEngineCommunicator();
        pfcTaxEngineCommunicator = new PFCTaxEngineCommunicator();
        commonOutputPEItinsLoader = new CommonOutputPEItinsLoader();

        s3Util = new S3Util();
        Properties p = ConfigurationReader.readProperties("pe-engines-taxes.properties");
        String fileBucket = p.getProperty("x1taxrecords.file.bucket").trim();
        String bucketObjectKey = p.getProperty("x1taxrecords.file.object.name").trim();

        // Check if object exists
        if (s3Util.doesObjectExist(fileBucket, bucketObjectKey)) {
            log.info("Loading X1 tax record data points from engine redis dump file...");
            long startTime = System.currentTimeMillis();
            InputStream inputStream = s3Util.getFileInputStream(fileBucket, bucketObjectKey);

            if (inputStream == null) {
                log.error("X1 tax record data points file could be empty in S3 bucket: " + fileBucket + " with key: " + bucketObjectKey);
                throw new FileNotFoundException("X1 tax datapoints file could be empty or not present - " + bucketObjectKey);
            }

            X1TaxRecordDataPointsLoader x1TaxRecordDataPointsLoader =
                    new X1TaxRecordDataPointsLoader();

            x1TaxRecordDataPointsMap = Collections.unmodifiableMap(
                    x1TaxRecordDataPointsLoader.loadTaxRecordDataPoints(inputStream)
            );
            log.info("DONE Loading X1 tax record data points from redis dump file. Time taken: " + (System.currentTimeMillis() - startTime) + " ms");
            log.info("X1 tax record data points loaded: " + x1TaxRecordDataPointsMap.size() + " entries");
        } else {
            log.error("X1 tax record data points file not found in S3 bucket: " + fileBucket + " with key: " + bucketObjectKey);
            throw new FileNotFoundException(" X1 tax datapoints file not found - " + bucketObjectKey);
        }

        // Initialize the PEItineraryProcessor with the required dependencies
        peItineraryTaxProcessor = new PEItineraryTaxProcessor(
                x1TaxRecordDataPointsMap,
                taxEngineCommunicator,
                pfcTaxEngineCommunicator
        );
    }

    /**
     * Process a list of PEItineraries in parallel
     *
     * @param peItineraries The list of PEItineraries to process
     * @param salesDate
     * @return A CompletableFuture that completes when all processing is done
     */
    private CompletableFuture<Void> processItineraries(List<PEItinerary> peItineraries, int salesDate) {
        log.info("Processing " + peItineraries.size() + " itineraries");

        // Create a bounded semaphore to limit the number of concurrent tasks
        Semaphore semaphore = new Semaphore(THREAD_COUNT * 2);

        // Create a list to hold all the futures
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Process each itinerary in parallel using parallel stream
        ConcurrentLinkedQueue<CompletableFuture<Void>> futureQueue = new ConcurrentLinkedQueue<>();

        peItineraries.parallelStream().forEach(itinerary -> {
            try {
                // Acquire a permit from the semaphore before submitting a new task
                semaphore.acquire();

                // Generate a unique query ID
                String queryId = QUERY_ID_PREFIX_3V + UUID.randomUUID();

                // Create a CompletableFuture for each itinerary
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        // Process the itinerary
                        peItineraryTaxProcessor.processPEItinerary(itinerary, queryId, salesDate);
                    } catch (Exception e) {
                        log.error("Error processing itinerary", e);
                    } finally {
                        // Release the permit back to the semaphore when the task is done
                        semaphore.release();
                    }
                }, executor);

                futureQueue.add(future);
            } catch (InterruptedException e) {
                log.error("Error acquiring semaphore", e);
            }
        });

        // Add all futures from the concurrent queue to the futures list
        futures.addAll(futureQueue);

        // Combine all futures into a single CompletableFuture
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
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

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Mandatory arguments - 'salesDate' with Format: yyyyMMdd"
                    + " and 'customer' need to be provided.");
        }

        String salesDateStr = args[0];
        if (!salesDateStr.matches("\\d{8}")) {
            throw new IllegalArgumentException("Invalid 'salesDate' format. Expected format: yyyyMMdd");
        }
        int salesDate = Integer.parseInt(salesDateStr);

        String customer = args[1];

        // Optional arguments
        int limit = 100;
        String schemaSuffix = "";

        if (args.length > 2) {
            try {
                limit = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                log.warn("Invalid 'limit' format. Using default value: 100");
            }
        }

        if (args.length > 3) {
            schemaSuffix = (args[3] != null) ? args[3].trim() : "";
        }

        // Log the arguments for verification
        log.info("Sales Date: " + salesDate);
        log.info("Customer: " + customer);
        log.info("Limit: " + limit);
        log.info("Schema Suffix: " + schemaSuffix);

        PEEnginesTaxesApplicationWithCommonOutput currentApp = new PEEnginesTaxesApplicationWithCommonOutput();

        try {

            List<PEItinerary> peItineraries;
            //Load the itins from common output
            peItineraries = currentApp.commonOutputPEItinsLoader.loadPEItins(salesDate, customer, schemaSuffix, limit);
            log.info("Loaded " + peItineraries.size() + " itineraries");

            if (peItineraries.isEmpty()) {
                log.warn("No itineraries found for the given parameters");
                return;
            }

            // Process the itineraries in parallel
            CompletableFuture<Void> processingFuture = currentApp.processItineraries(peItineraries, salesDate);

            // Wait for all processing to complete
            processingFuture.join();

            log.info("✅ Processing complete.");
        } catch (Exception e) {
            log.error("Error processing itineraries", e);
        } finally {
            // Ensure resources are cleaned up
            currentApp.shutdown();
        }
    }
}
