package com.threevictors.aws.priceeye.taxes.loader;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import com.threevictors.aws.priceeye.taxes.dao.MetadataReader;
import lombok.Data;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

//INPUT CSV DATA GENERATION CLASS for data with connections
//NOTE: Run this class to generate the unique itineraries from the large input files.
// The output will be written to a text file in the specified directory.
public class UniqueMktsPEItinsLoaderConnections {
    private static final Set<String> seenKeys = ConcurrentHashMap.newKeySet();
    private static Map<String, String> airportCountryCodeMap;
    private static Set<String> usDomesticAirports;
    // Number of reader threads to use - adjust based on IO capabilities
    private static final int NUM_READER_THREADS = 8;
    // Size of the chunk queue - buffer between readers and processors
    private static final int CHUNK_QUEUE_SIZE = 500;
    // Size of each chunk in lines - adjust based on memory constraints
    private static final int CHUNK_SIZE = 5000;
    // Performance monitoring
    private static final AtomicLong totalLinesRead = new AtomicLong(0);
    private static final AtomicLong totalLinesProcessed = new AtomicLong(0);
    private static final long startTime = System.currentTimeMillis();

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

        // Create a larger queue to handle more tasks
        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(4_000_000);

        // Create a thread pool with more threads to process the data
        int processorCount = Runtime.getRuntime().availableProcessors();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                processorCount * 4,
                processorCount * 8,
                10, TimeUnit.SECONDS,
                workQueue,
                new BlockingRejectedExecutionHandler());

        // Create a queue for chunks of CSV data
        BlockingQueue<List<String[]>> chunkQueue = new ArrayBlockingQueue<>(CHUNK_QUEUE_SIZE);

        // Flag to signal when all file reading is done
        AtomicBoolean readingComplete = new AtomicBoolean(false);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            // First, read the header
            CSVParser parser = new CSVParserBuilder()
                    .withSeparator(',')
                    .build();

            try (CSVReader headerReader = new CSVReaderBuilder(new FileReader(inputFile)).withCSVParser(parser).build()) {
                String[] header = headerReader.readNext();

                if (header != null) {
                    writer.write(String.join(",", header));
                    writer.newLine();
                }

                Map<String, Integer> headerMap = createHeaderIndexMap(header);
                final AtomicLong count = new AtomicLong(0);

                // Create and start the file reader threads
                ExecutorService readerExecutor = Executors.newFixedThreadPool(NUM_READER_THREADS);

                // Calculate approximate file size and chunk positions
                long fileSize = inputFile.length();
                long chunkSize = fileSize / NUM_READER_THREADS;

                // Start reader threads
                for (int i = 0; i < NUM_READER_THREADS; i++) {
                    long startPosition = i * chunkSize;
                    long endPosition = (i == NUM_READER_THREADS - 1) ? fileSize : (i + 1) * chunkSize;

                    readerExecutor.submit(new FileReaderWorker(
                            inputFilePath, 
                            startPosition, 
                            endPosition, 
                            chunkQueue, 
                            readingComplete,
                            i == 0 // First thread skips the header line
                    ));
                }

                // Shutdown the reader executor (it will finish all submitted tasks)
                readerExecutor.shutdown();

                // Create consumer threads to process chunks from the queue
                int numConsumers = processorCount * 2;
                ExecutorService consumerExecutor = Executors.newFixedThreadPool(numConsumers);

                for (int i = 0; i < numConsumers; i++) {
                    consumerExecutor.submit(() -> {
                        try {
                            while (!readingComplete.get() || !chunkQueue.isEmpty()) {
                                List<String[]> chunk = null;
                                try {
                                    // Poll with timeout to check the readingComplete flag periodically
                                    chunk = chunkQueue.poll(100, TimeUnit.MILLISECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }

                                if (chunk != null) {
                                    for (String[] values : chunk) {
                                        executor.execute(new ParseRunnable(headerMap, values, writer, count));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }

                // Wait for all reader threads to complete
                try {
                    readerExecutor.awaitTermination(60, TimeUnit.MINUTES);
                    // Signal that reading is complete
                    readingComplete.set(true);
                    // Wait for consumer threads to complete
                    consumerExecutor.shutdown();
                    consumerExecutor.awaitTermination(60, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                // Wait for all processing to complete
                executor.shutdown();
                try {
                    executor.awaitTermination(60, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } catch (CsvValidationException e) {
                throw new RuntimeException(e);
            }

            writer.flush();
        } catch (IOException e) {
            System.err.println("Error during processing: " + e.getMessage());
            System.exit(1);
        }

        // Calculate and print final performance statistics
        long endTime = System.currentTimeMillis();
        long totalTimeSeconds = (endTime - startTime) / 1000;
        long totalLinesReadFinal = totalLinesRead.get();
        long totalLinesProcessedFinal = totalLinesProcessed.get();

        System.out.println("\n--- Performance Summary ---");
        System.out.println("Total lines read: " + totalLinesReadFinal);
        System.out.println("Total lines processed: " + totalLinesProcessedFinal);
        System.out.println("Total time: " + totalTimeSeconds + " seconds");

        if (totalTimeSeconds > 0) {
            System.out.println("Average read rate: " + (totalLinesReadFinal / totalTimeSeconds) + " lines/sec");
            System.out.println("Average processing rate: " + (totalLinesProcessedFinal / totalTimeSeconds) + " lines/sec");
        }

        // Print CPU utilization information
        System.out.println("\n--- CPU Utilization Information ---");
        System.out.println("Available processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Reader threads: " + NUM_READER_THREADS);
        System.out.println("Consumer threads: " + (Runtime.getRuntime().availableProcessors() * 2));
        System.out.println("Processing thread pool: " + (Runtime.getRuntime().availableProcessors() * 4) + 
                          " to " + (Runtime.getRuntime().availableProcessors() * 8) + " threads");
        System.out.println("Total active threads at end: " + Thread.activeCount());

        // Print memory usage information
        long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long totalMemory = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long freeMemory = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;

        System.out.println("\n--- Memory Usage Information ---");
        System.out.println("Max memory: " + maxMemory + " MB");
        System.out.println("Total memory: " + totalMemory + " MB");
        System.out.println("Used memory: " + usedMemory + " MB");
        System.out.println("Free memory: " + freeMemory + " MB");

        System.out.println("Done. Output written to " + outputFile.getAbsolutePath());
    }

    static class ParseRunnable implements Runnable {

        Map<String, Integer> headerMap;
        String [] values;
        BufferedWriter writer;
        AtomicLong count;
        // Use a StringBuilder to reduce string concatenation overhead
        private static final ThreadLocal<StringBuilder> stringBuilderCache = 
            ThreadLocal.withInitial(() -> new StringBuilder(1024));

        public ParseRunnable(Map<String, Integer> headerMap, String[] values, BufferedWriter writer, AtomicLong count) {
            this.headerMap = headerMap;
            this.values = values;
            this.writer = writer;
            this.count = count;
        }

        public void run() {
            // Build all legs in parallel using a more efficient approach
            Leg[] legs = new Leg[4];
            legs[0] = buildLeg(values, headerMap, true, 1);  // obl1Leg
            legs[1] = buildLeg(values, headerMap, true, 2);  // obl2Leg
            legs[2] = buildLeg(values, headerMap, false, 1); // ibl1Leg
            legs[3] = buildLeg(values, headerMap, false, 2); // ibl2Leg

            // Generate key more efficiently
            String key = generateKey(legs[0], legs[1], legs[2], legs[3]);

            if (key != null && seenKeys.add(key)) {
                try {
                    // Prepare the output line outside the synchronized block
                    StringBuilder sb = stringBuilderCache.get();
                    sb.setLength(0); // Clear the StringBuilder

                    for (int i = 0; i < values.length; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append(values[i]);
                    }
                    sb.append('\n');

                    // Minimize the synchronized block to reduce contention
                    synchronized (writer) {
                        writer.write(sb.toString());
                    }
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }

            // Increment both counters
            long actualCount = count.incrementAndGet();
            long processedCount = totalLinesProcessed.incrementAndGet();

            // Log progress periodically
            if (processedCount % 1_000_000 == 0) {
                long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
                if (elapsedSeconds > 0) {
                    long readRate = totalLinesRead.get() / elapsedSeconds;
                    long processRate = processedCount / elapsedSeconds;

                    System.out.println("Processed: " + processedCount + 
                                      ", Read: " + totalLinesRead.get() + 
                                      ", Read rate: " + readRate + " lines/sec" +
                                      ", Process rate: " + processRate + " lines/sec" +
                                      ", Queue size: " + (totalLinesRead.get() - processedCount) +
                                      ", Active threads: " + Thread.activeCount());
                }
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
                if (hoursBetween(obl1Leg, obl2Leg) >= 12) {
                    return null;
                }
            }

            // Is the inbound connecting airport in the US
            if (isUSAirport( ibl1Leg.getDestinationAirportCode() )) {
                if (hoursBetween(ibl1Leg, ibl2Leg) >= 12) {
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

    // Worker class for reading chunks of the file in parallel
    static class FileReaderWorker implements Runnable {
        private final String filePath;
        private final long startPosition;
        private final long endPosition;
        private final BlockingQueue<List<String[]>> chunkQueue;
        private final AtomicBoolean readingComplete;
        private final boolean skipHeader;
        // Buffer size for reading - larger buffer for better performance
        private static final int BUFFER_SIZE = 8192 * 4;

        public FileReaderWorker(String filePath, long startPosition, long endPosition, 
                               BlockingQueue<List<String[]>> chunkQueue, AtomicBoolean readingComplete,
                               boolean skipHeader) {
            this.filePath = filePath;
            this.startPosition = startPosition;
            this.endPosition = endPosition;
            this.chunkQueue = chunkQueue;
            this.readingComplete = readingComplete;
            this.skipHeader = skipHeader;
        }

        @Override
        public void run() {
            long startTime = System.currentTimeMillis();
            long linesReadByThisThread = 0;

            try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
                // Position the file pointer at the start position
                raf.seek(startPosition);

                // If not at the beginning of the file, find the next line start
                if (startPosition > 0) {
                    // Read until we find a newline character
                    int b;
                    while ((b = raf.read()) != -1 && b != '\n') {
                        // Just advance to the next line
                    }
                } else if (skipHeader) {
                    // Skip the header line if this is the first chunk
                    raf.readLine();
                }

                // Create a buffered reader for better performance
                InputStream is = new FileInputStream(raf.getFD());
                BufferedInputStream bis = new BufferedInputStream(is, BUFFER_SIZE);
                BufferedReader br = new BufferedReader(new InputStreamReader(bis), BUFFER_SIZE);

                // Create a CSV parser
                CSVParser parser = new CSVParserBuilder()
                        .withSeparator(',')
                        .build();

                // Read the file in chunks
                long currentPosition = raf.getFilePointer();
                List<String[]> currentChunk = new ArrayList<>(CHUNK_SIZE);
                String line;

                while (currentPosition < endPosition && (line = br.readLine()) != null) {
                    currentPosition = raf.getFilePointer();

                    try {
                        // Parse the CSV line
                        String[] values = parser.parseLine(line);
                        currentChunk.add(values);

                        // Increment the total lines read counter
                        long linesRead = totalLinesRead.incrementAndGet();
                        linesReadByThisThread++;

                        // Log progress periodically
                        if (linesRead % 1_000_000 == 0) {
                            long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
                            if (elapsedSeconds > 0) {
                                System.out.println("Lines read: " + linesRead + 
                                                  ", Rate: " + (linesRead / elapsedSeconds) + 
                                                  " lines/sec, Thread: " + Thread.currentThread().getName() +
                                                  ", Queue size: " + chunkQueue.size() + "/" + CHUNK_QUEUE_SIZE);
                            }
                        }

                        // If we've reached the chunk size, add to queue and create a new chunk
                        if (currentChunk.size() >= CHUNK_SIZE) {
                            // Put the chunk in the queue, waiting if necessary
                            chunkQueue.put(currentChunk);
                            currentChunk = new ArrayList<>(CHUNK_SIZE);
                        }
                    } catch (Exception e) {
                        System.err.println("Error parsing line: " + e.getMessage());
                    }
                }

                // Add any remaining lines to the queue
                if (!currentChunk.isEmpty()) {
                    chunkQueue.put(currentChunk);
                }

                // Log thread completion statistics
                long threadTime = System.currentTimeMillis() - startTime;
                System.out.println("Reader thread " + Thread.currentThread().getName() + 
                                  " completed: Read " + linesReadByThisThread + 
                                  " lines in " + (threadTime / 1000) + " seconds, " +
                                  "Rate: " + (threadTime > 0 ? (linesReadByThisThread * 1000 / threadTime) : 0) + 
                                  " lines/sec");

            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Error in file reader worker: " + e.getMessage());
                e.printStackTrace();
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
