package com.threevictors.aws.priceeye.exp;

import com.opencsv.CSVReader;
import com.threevictors.aws.configreader.configuration.reader.heavy.ConfigurationReader;
import com.threevictors.aws.data.priceeye.PEItinerary;
import com.threevictors.aws.priceeye.exp.loader.PEItinerariesLoader;
import com.threevictors.aws.priceeye.exp.loader.X1TaxRecordDataPointsLoader;
import com.threevictors.aws.priceeye.exp.model.taxengine.response.*;
import com.threevictors.aws.priceeye.exp.taxengine.PFCTaxEngineCommunicator;
import com.threevictors.aws.priceeye.exp.taxengine.TaxEngineCommunicator;

import com.threevictors.common.aws.s3.S3Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.io.FileReader;

public class PEEnginesTaxesExpApplicationParallelAlternate {

    private static final Logger log = LogManager.getLogger(PEEnginesTaxesExpApplicationParallelAlternate.class);

    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 2; // Doubled thread count to handle I/O-bound operations

    private static final String QUERY_ID_PREFIX_3V = "3v-";

    private Map<String, X1TaxRecordDataPoints> x1TaxRecordDataPointsMap;

    private ExecutorService executor;

    private TaxEngineCommunicator taxEngineCommunicator;
    private PFCTaxEngineCommunicator pfcTaxEngineCommunicator;
    private PEItineraryTaxProcessor peItineraryTaxProcessor;

    private S3Util s3Util;

    //List of mismatched tax line numbers with connections. Depends on the source file read from the command line argument.
    List<Integer> misMatchTaxLineNumbers = Arrays.asList(


            //OI taxes 8 lines - openjaw removed
            //2, 20, 411, 593, 594, 911, 912, 1316

            //OY taxes 8 lines - openjaw removed
            //46, 496, 503, 572, 1043, 1046, 1047, 1802

            //WY taxes 8 lines
            //4392, 4393, 4394, 4395, 4396, 4397, 4398, 4399
    );

    public PEEnginesTaxesExpApplicationParallelAlternate() throws Exception {
        // Create a thread pool with the specified number of threads
        executor = Executors.newFixedThreadPool(THREAD_COUNT);

        taxEngineCommunicator = new TaxEngineCommunicator();
        pfcTaxEngineCommunicator = new PFCTaxEngineCommunicator();

        s3Util = new S3Util();
        Properties p = ConfigurationReader.readProperties( "pe-engines-taxes.properties");
        String fileBucket = p.getProperty("x1taxrecords.file.bucket").trim();

        //Not correct to give fullpath
        //String bucketObjectKey = "s3://"+fileBucket+"/x1datapoints_all_taxrecs_from_engine_redis.txt";
        String bucketObjectKey = p.getProperty("x1taxrecords.file.object.name").trim();

        //Check if object exists
        if(s3Util.doesObjectExist(fileBucket, bucketObjectKey)) {

            X1TaxRecordDataPointsLoader x1TaxRecordDataPointsLoader = new X1TaxRecordDataPointsLoader();

            log.info("Loading X1 tax record data points from engine redis dump file...");
            long startTime = System.currentTimeMillis();
            //"x1datapoints_all_taxrecs_from_engine_redis.txt"
            InputStream inputStream = s3Util.getFileInputStream( fileBucket, bucketObjectKey);

            if (inputStream == null) {
                log.error("X1 tax record data points file could be empty in S3 bucket: " + fileBucket + " with key: " + bucketObjectKey);
                throw new FileNotFoundException("X1 tax datapoints file could be empty or not present - " + bucketObjectKey);
            }
            x1TaxRecordDataPointsMap = Collections.unmodifiableMap(
                    x1TaxRecordDataPointsLoader.loadTaxRecordDataPoints(inputStream)
            );
            log.info("DONE Loading X1 tax record data points from redis dump file. Time taken: " + (System.currentTimeMillis() - startTime) + " ms");
            log.info("X1 tax record data points loaded: " + x1TaxRecordDataPointsMap.size() + " entries");
        }
        else {
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
     * Process a source file containing PEItinerary data
     * @param source The source file to read
     * @param ticketDate The ticket date to use
     * @param arePEItinsOneWay Whether the itineraries are one-way
     * @return A CompletableFuture that completes when all processing is done
     */
    private CompletableFuture<Void> processSourceFile(File source, String ticketDate, boolean arePEItinsOneWay) {
        log.info("Processing source file: " + source.getAbsolutePath());

        // Use a separate thread pool for parsing to avoid blocking the main thread
        int parserThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        ExecutorService parserExecutor = Executors.newFixedThreadPool(parserThreads);

        // Create a CompletableFuture to represent the overall processing
        CompletableFuture<Void> processingFuture = new CompletableFuture<>();

        // Create a bounded queue to limit the number of lines being processed at once
        // This prevents overwhelming the system with too many concurrent tasks
        Semaphore semaphore = new Semaphore(THREAD_COUNT * 2);

        // Create a list to hold all the futures
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Start a separate thread to read the file line by line
        Thread fileReaderThread = new Thread(() -> processSourceFile(source.getAbsolutePath(), ticketDate, arePEItinsOneWay, processingFuture, semaphore, futures));

        // Start the file reader thread
        fileReaderThread.start();

        // Return a future that completes when all processing is done and cleans up resources
        return processingFuture.whenComplete((result, ex) -> {
            try {
                // Wait for the file reader thread to finish
                fileReaderThread.join();
            } catch (InterruptedException e) {
                log.error("Error waiting for file reader thread", e);
            } finally {
                // Shutdown the parser executor
                parserExecutor.shutdown();
                try {
                    if (!parserExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
                        log.warn("Parser executor did not terminate in the specified time.");
                    }
                } catch (InterruptedException e) {
                    log.error("Error shutting down parser executor", e);
                }
            }
        });
    }



    private void processSourceFile(String source, String ticketDate, boolean arePEItinsOneWay, CompletableFuture<Void> processingFuture, Semaphore semaphore, List<CompletableFuture<Void>> futures) {
        try (CSVReader reader = new CSVReader(new FileReader(source))) {
            String[] line;
            int lineNumber = 0;
            boolean skipHeader = true;

            // Read the file line by line
            while ((line = reader.readNext()) != null) {
                lineNumber++;

                // Skip header if present
                if (skipHeader && line[0].equals("validatingcarrier")) {
                    skipHeader = false;
                    continue;
                }

                // Filter lines if misMatchTaxLineNumbers is not empty
                // Uncomment this when reading mismatched tax line numbers
                /*if (!misMatchTaxLineNumbers.isEmpty() && !misMatchTaxLineNumbers.contains(lineNumber)) {
                    continue;
                }*/

                final String[] currentLine = line;
                final int currentLineNumber = lineNumber;

                // Acquire a permit from the semaphore before submitting a new task
                // This will block if too many tasks are already in progress
                semaphore.acquire();

                // Create a CompletableFuture for each line
                CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                        processLine(currentLine, currentLineNumber, ticketDate, arePEItinsOneWay, semaphore), executor)
                        .exceptionally(ex -> {
                            log.error("Error processing itinerary", ex);
                            return null;
                        });

                futures.add(future);
            }

            log.info("Finished reading file, processed " + (lineNumber - (skipHeader ? 0 : 1)) + " lines");

            // Combine all futures into a single CompletableFuture
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            );

            // When all futures complete, complete the processing future
            allFutures.whenComplete((result, ex) -> {
                if (ex != null) {
                    processingFuture.completeExceptionally(ex);
                } else {
                    processingFuture.complete(null);
                }
            });

        } catch (Exception e) {
            log.error("Error reading source file", e);
            processingFuture.completeExceptionally(e);
        }
    }


    private void processLine(String [] currentLine, int currentLineNumber, String ticketDate, boolean arePEItinsOneWay, Semaphore semaphore) {
        try {
            List<String> lineList = new ArrayList<>(Arrays.asList(currentLine));
            lineList.add(String.valueOf(currentLineNumber));

            PEItinerary itinerary = null;
            if (!arePEItinsOneWay) {
                // Itineraries with connections
                itinerary = PEItinerariesLoader.parsePEItineraryLineWithConnections(lineList);

                // Set the ticket date on the itinerary object
                itinerary.setDuration(Integer.parseInt(ticketDate));
            } else {
                // TODO: Implement this if needed
            }

            if (itinerary != null && itinerary.getOutboundLegs() != null) {
                // Generate the queryId
                String queryId = QUERY_ID_PREFIX_3V + UUID.randomUUID();

                // Process the itinerary
                peItineraryTaxProcessor.processPEItinerary(itinerary, queryId);
            }
        } catch (Exception e) {
            log.error("Error processing line " + currentLineNumber, e);
        } finally {
            // Release the permit back to the semaphore when the task is done
            semaphore.release();
        }
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
            throw new IllegalArgumentException("Mandatory arguments - 'ticketDate' with Format: yyMMdd"
                    + " and input file 'uniqueMktsPEItinsFilePath' need to be provided.");
        }

        String ticketDate = args[0];
        if (!ticketDate.matches("\\d{6}")) {
            throw new IllegalArgumentException("Invalid 'ticketDate' format. Expected format: yyMMdd");
        }

        String uniqueMktsPEItinsFilePath = args[1];

        boolean arePEItinsOneWay = false; // Default to send as round trip

        if (args.length > 2) {
            //3rd optional argument to indicate whether to send as one-way or round trip
            arePEItinsOneWay = Boolean.parseBoolean(args[2]);
        }

        // Log the arguments for verification
        log.info("Ticket Date: " + ticketDate);
        log.info("uniqueMktsPEItinsFilePath: " + uniqueMktsPEItinsFilePath);
        log.info("arePEItinsOneWay: " + arePEItinsOneWay);

        PEEnginesTaxesExpApplicationParallelAlternate currentApp = new PEEnginesTaxesExpApplicationParallelAlternate();

        File source = new File(uniqueMktsPEItinsFilePath);

        try {
            // Process the source file and wait for completion
            CompletableFuture<Void> processingFuture = currentApp.processSourceFile(source, ticketDate, arePEItinsOneWay);

            // Wait for all processing to complete
            processingFuture.join();

            log.info("✅ Processing complete.");
        } catch (Exception e) {
            log.error("Error processing file", e);
        } finally {
            // Ensure resources are cleaned up
            currentApp.shutdown();
        }
    }
}