package com.hftparser.main;


import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.hftparser.config.BadConfigFileError;
import com.hftparser.config.ConfigFactory;
import com.hftparser.config.MarketOrderCollectionConfig;
import com.hftparser.config.ParseRunConfig;
import com.hftparser.containers.Backoffable;
import com.hftparser.containers.WaitFreeQueue;
import com.hftparser.readers.ArcaParser;
import com.hftparser.readers.DataPoint;
import com.hftparser.readers.GzipReader;
import com.hftparser.readers.MarketOrderCollectionFactory;
import com.hftparser.writers.HDF5Writer;

import java.io.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// WARNING: TURNING ON ASSERTIONS BREAKS SOMETHING INSIDE OF THE CISD CODE
// NEVER, EVER, EVER TURN ON ASSERTIONS

// NOTE: COMPACT data layout is not extensible.
class ParseRun {
    private static int LINE_QUEUE_SIZE;
    private static int POINT_QUEUE_SIZE;

//    private static int MIN_BACKOFF;
//    private static int MAX_BACKOFF;

    private static class Args {
        @Parameter
        private List<String> parameters = new ArrayList<>();

        @Parameter(names = {"-symbols", "-s"}, description = "CSV containing symbols")
        private String symbolPath;

        @Parameter(names = {"-book ", "-b"}, description = "Gzipped CSV of book data")
        private String bookPath;

        @Parameter(names = {"-out", "-o"}, description = "Output .h5 file")
        private String outPath;

        @Parameter(names = {"-config", "-c"}, description = "JSON-formatted config file")
        private String configPath;

//        @Parameter(names = {"-stats", "-s"}, description = "Output file for run statistics")
//        private String statsPath;
    }


    public static void main(String[] argv) {
        Args args = new Args();
        new JCommander(args, argv);
        String[] symbols = null;
        InputStream GzipInstream = null;
        GzipReader gzipReader = null;
        ArcaParser parser;
        HDF5Writer writer;
        File outFile;
        Thread readerThread;
        Thread parserThread;
        Thread writerThread;
        Thread[] allThreads;
        ConfigFactory configFactory = null;

        long startTime = System.currentTimeMillis();
        long endTime;

        if(args.bookPath == null || args.outPath == null || args.configPath == null) {
            System.out.println("Book path and output path must be specified.");
            return;
        }

        if(args.symbolPath == null){
            symbols = new String[]{
            "SPY", "DIA", "QQQ",
            "XLK", "XLF", "XLP", "XLE", "XLY", "XLV", "XLB",
            "VCR", "VDC", "VHT", "VIS", "VAW", "VNQ", "VGT", "VOX", "VPU",
            "XOM", "RDS", "BP",
            "HD", "LOW", "XHB",
            "MS", "GS", "BAC", "JPM", "C",
            "CME", "NYX",
            "AAPL", "MSFT", "GOOG", "CSCO",
            "GE", "CVX", "JNJ", "IBM", "PG", "PFE",
            };
        } else {
            File symbolFile = new File(args.symbolPath);
            try {
                symbols = parseSymbolFile(symbolFile);
            } catch (IOException e) {
                printErrAndExit("Got error opening symbol file:" + e.toString());
            }
        }

        try {
            GzipInstream = new FileInputStream(new File(args.bookPath));
        } catch (FileNotFoundException e) {
            printErrAndExit("Error opening book file.");
        }

        try {
            configFactory = ConfigFactory.fromPath(args.configPath);
        } catch (BadConfigFileError | IOException e) {
            printErrAndExit("There was a problem loading your config file: " + e.toString());
        }


        ParseRunConfig parseRunConfig = configFactory.getParseRunConfig();
        setProperties(parseRunConfig);

        Backoffable sBackoffOne = parseRunConfig.makeBackoffFor(ParseRunConfig.BackoffType.String);
        Backoffable sBackoffTwo = parseRunConfig.makeBackoffFor(ParseRunConfig.BackoffType.String);

        Backoffable dBackoffOne = parseRunConfig.makeBackoffFor(ParseRunConfig.BackoffType.DataPoint);
        Backoffable dBackoffTwo = parseRunConfig.makeBackoffFor(ParseRunConfig.BackoffType.DataPoint);

        outFile = new File(args.outPath);
        WaitFreeQueue<String> linesReadQueue = new WaitFreeQueue<>(LINE_QUEUE_SIZE,
                sBackoffOne,
                sBackoffTwo);
        WaitFreeQueue < DataPoint > dataPointQueue = new WaitFreeQueue<>(POINT_QUEUE_SIZE,
                dBackoffOne,
                dBackoffTwo);

        try {
            gzipReader = new GzipReader(GzipInstream, linesReadQueue);
        } catch(IOException e) {
            printErrAndExit("Error opening book file for reading: " + e.toString());
        }

        MarketOrderCollectionConfig marketOrderCollectionConfig = configFactory.getMarketOrderCollectionConfig();
        MarketOrderCollectionFactory orderCollectionFactory =
                new MarketOrderCollectionFactory(marketOrderCollectionConfig);

        parser = new ArcaParser(symbols,
                                linesReadQueue,
                                dataPointQueue,
                                orderCollectionFactory,
                                configFactory.getArcaParserConfig());
        writer = new HDF5Writer(dataPointQueue, outFile, configFactory.getHdf5WriterConfig(),
                configFactory.getHdf5CompoundDSBridgeConfig());

        readerThread = new Thread(gzipReader);
        parserThread = new Thread(parser);
        writerThread = new Thread(writer);

        allThreads = new Thread[] {
                readerThread,
                parserThread,
                writerThread
        };

        System.out.println("Starting parser.");
        for(Thread t : allThreads){
            t.start();
        }
        System.out.println("Parser started. Waiting.");

        try {
            for (Thread t : allThreads) {
                t.join();
            }
        } catch (Exception e) {
            System.out.println("A thread threw an exception:" + e.toString());
            System.out.println("Stopping.");
            return;
        }

        endTime = System.currentTimeMillis();
        System.out.println("Successfully created " + args.outPath);
        printRunTime(startTime, endTime);

        System.out.println("Information for String queue:");
        linesReadQueue.printUsage();
        System.out.println("Information for Datapoint queue:");
        dataPointQueue.printUsage();
    }

    public static Calendar startCalendarFromFilename(String bookPath) {
//        format is YYYYMMDD
        String datePattern = "\\D+(\\d{4})(\\d{2})(\\d{2})\\.csv\\.gz";
        Pattern dateRe = Pattern.compile(datePattern);
        Matcher matcher = dateRe.matcher(bookPath);
        Calendar startDate;
        if (matcher.matches()) {
            System.out.println("Got match on:" + matcher.toString());
            System.out.println("Group 0:" + matcher.group(0));

            startDate = Calendar.getInstance();

//            clear, otherwise it holds on to the current hour, second, etc
            startDate.clear();

//            note that months are 0-based (i.e. january == 0)
            startDate.set(Integer.valueOf(matcher.group(1)),
                          Integer.valueOf(matcher.group(2)) - 1,
                          Integer.valueOf(matcher.group(3)));

            return startDate;
        }

        return null;
    }


    private static void printRunTime(long startMs, long endMs) {
        double diff = endMs - startMs;

        System.out.printf("Total time: %.3f sec\n", diff / 1000);
    }

    private static void setProperties(ParseRunConfig config) {
        LINE_QUEUE_SIZE = config.getLine_queue_size();
        POINT_QUEUE_SIZE = config.getPoint_queue_size();


    }

    private static void printErrAndExit(String err) {
        System.out.println(err);
        System.out.println("Exiting");
        System.exit(1);
    }

    private static String[] parseSymbolFile(File symbolFile)
            throws IOException {
        ArrayList<String> ret = new ArrayList<>();
        String line;
        BufferedReader reader = new BufferedReader(new FileReader(symbolFile));

        while((line = reader.readLine()) != null) {
            ret.add(line.trim());
        }
        reader.close();

        return ret.toArray(new String[ret.size()]);
    }

}