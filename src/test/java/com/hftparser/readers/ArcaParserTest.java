package com.hftparser.readers;

import com.hftparser.config.ArcaParserConfig;
import com.hftparser.containers.MarketOrderCollectionFactory;
import com.hftparser.containers.WaitFreeQueue;
import com.hftparser.data.DataPoint;
import com.hftparser.data.PoisonDataPoint;
import com.hftparser.data.ValidDataPoint;
import com.hftparser.main.ParseRun;
import org.apache.commons.lang.mutable.MutableBoolean;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Calendar;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

// TODO: add a test to check a modify followed by a delete
@SuppressWarnings("UnusedAssignment")
@RunWith(JUnit4.class)
public class ArcaParserTest {
    private final String[] TEST_TICKERS = {"FOO", "BAR"};

    private final String TEST_ADD_BUY1 = "A,1,12884901908,B,B,1000,FOO,2.75,28800,737,B,AARCA,";
    private final String TEST_ADD_BUY2 = "A,10,12884902050,B,B,3200,FOO,0.98,28800,737,B,AARCA,";

    private final String TEST_ADD_BUY1_DUP = "A,2,12884901908,B,B,1000,FOO,2.75,28800,737,B,AARCA,";

    private final String TEST_ADD_SELL1 = "A,9,12884902687,B,S,30000,FOO,0.02,28800,739,B,AARCA,";
    private final String TEST_ADD_SELL2 = "A,12,12884902091,B,S,200000,BAR,0.0195,28800,740,B,AARCA,";

    private final String TEST_DELETE_BUY1 = "D,52,12884901908,28800,857,FOO,B,B,AARCA,B,";

    private final String TEST_MODIFY_BUY1 = "M,43,12884901908,900,0.3825,29909,390,FOO,B,B,AARCA,B,";
    private final String TEST_MODIFY_BUY2 = "M,50,12884902050,3000,0.98,33643,922,FOO,B,B,AARCA,B,";

    private final String TEST_WHOLE = "A,1,12884901908,B,B,1000,FOO,275,28800,737,B,AARCA,";

    private final String TEST_OVERFLOW = "A,24,4503612512341875,P,S,100,FOO,99999.9844,31830,137,E,AARCA,";

    private final String TEST_TIMESTAMPS = "A,1,3940662558851079,P,B,100,FOO,116.5200,14400,566,E,AARCA";

    private final String TEST_ALL_DEC = "A,1,3940662558851079,P,B,100,FOO,.123456,14400,566,E,AARCA";

    private final String TEST_ERRORS = "A,1,3940662558851079,P,B,100,FOO,.12345678,14400,566,E,AARCA";

    private final String TEST_EMPTY_MS = "A,1,1,P,B,2500,FOO,1,28800,,E,ARCAX,";

    MarketOrderCollectionFactory collectionFactory;
    private WaitFreeQueue<String> inQ;
    private WaitFreeQueue<DataPoint> outQ;
    private ArcaParser parser;


    @Before
    public void setUp() {
        setParser(new MutableBoolean());
    }

    public void setParser(MutableBoolean mutableBoolean) {
/* We need to reset this in b/t tests, otherwise the static value that's set from the previous run causes tests to
fail */
        Record.setStartTimestamp(0);

        collectionFactory = new MarketOrderCollectionFactory();
        inQ = new WaitFreeQueue<>(5);
        outQ = new WaitFreeQueue<>(5);
        parser = new ArcaParser(TEST_TICKERS,
                                inQ,
                                outQ,
                                collectionFactory,
                                ArcaParserConfig.getDefault(),
                                mutableBoolean);
    }


    @Test
    public void testSetStartDate() throws Exception {
        Calendar toSet = Calendar.getInstance();
        toSet.clear();
        toSet.set(2014, Calendar.AUGUST, 5);
        toSet.setTimeZone(parser.getDefaultTz());
        //        toSet.setTimeZone(TimeZone.getTimeZone("UTC"));

        long expectedStart = 1407211200l * 1000000l;
        long expectedForDay = 28800l * 1000000l + 737l * 1000l;
        long expected = expectedStart + expectedForDay;
        long actual;
        DataPoint actualPoint;

        parser.setStartCalendar(toSet);

        assertThat(parser.getStartTimestamp(), is(expectedStart));

        inQ.enq(TEST_ADD_BUY1);

        runParserThread();

        actualPoint = outQ.deq();

        assertNotNull(actualPoint);

        actual = actualPoint.getTimeStamp();

        System.out.println("Difference from start: " + (actual - expectedStart));

        assertThat(actual, is(expected));
    }

    @Test
    public void testTimestamps() throws Exception {
        Calendar toSet = ParseRun.startCalendarFromFilename("arcabookftp20101102.csv.gz");
        long expected = 1288684800566000l;

        parser.setStartCalendar(toSet);

        inQ.enq(TEST_TIMESTAMPS);

        runParserThread();

        assertThat(outQ.deq().getTimeStamp(), is(expected));
    }

    @Test
    public void testOverflow() throws Exception {
        inQ.enq(TEST_OVERFLOW);
        ArcaParser parser = this.parser;

        runParserThread(parser);

        long[][] expectedSells = new long[][]{{99999984400l, 100l}};

        DataPoint expected = new ValidDataPoint("FOO", new long[][]{}, expectedSells, 31830137000l, 24);
        DataPoint actual = outQ.deq();

        assertThat(actual, equalTo(expected));
    }


    @Test
    public void testModifyThenDelete() throws Exception {

        ArcaParser parser = this.parser;

        inQ.enq(TEST_ADD_BUY1);
        inQ.enq(TEST_MODIFY_BUY1);
        inQ.enq(TEST_DELETE_BUY1);

        runParserThread(parser);

        long[][] expectedOneBuy = {{2750000, 1000}};

        long[][] expectedTwoBuy = {{382500, 900}};

        DataPoint buyExpected = new ValidDataPoint("FOO", expectedOneBuy, new long[][]{}, 28800737000l, 1);

        DataPoint modifyExpected = new ValidDataPoint("FOO", expectedTwoBuy, new long[][]{}, 29909390000l, 43);

        DataPoint deleteExpected = new ValidDataPoint("FOO", new long[][]{}, new long[][]{}, 28800857000l, 52);

        DataPoint one = outQ.deq();
        DataPoint two = outQ.deq();
        DataPoint three = outQ.deq();

        System.out.println("Got one: " + one.toString());
        System.out.println("Got two: " + two.toString());
        System.out.println("Got three: " + three.toString());

        assertTrue(one.equals(buyExpected));
        assertTrue(two.equals(modifyExpected));
        assertTrue(three.equals(deleteExpected));
    }

    @Test
    public void testEmptyMs() throws Exception {
        inQ.enq(TEST_EMPTY_MS);
        runParserThread();

        DataPoint expected = new ValidDataPoint("FOO", new long[][]{{1000000l, 2500l}}, new long[][]{}, 28800000000l, 1);

        assertThat(outQ.deq(), equalTo(expected));
    }

    private void runParserThread(ArcaParser parser) throws InterruptedException {
        Thread runThread = new Thread(parser);
        runThread.start();
        Thread.sleep(100);
    }

    private void runParserThread() throws InterruptedException {
        runParserThread(parser);
    }

    @Test
    public void testIdenticalModifiesCaching() throws Exception {
        collectionFactory.setDoCaching(true);

        WaitFreeQueue<String> inQ = new WaitFreeQueue<>(5);
        WaitFreeQueue<DataPoint> outQ = new WaitFreeQueue<>(5);

        inQ.enq(TEST_ADD_BUY1);
        inQ.enq(TEST_MODIFY_BUY1);
        inQ.enq(TEST_MODIFY_BUY1);

        ArcaParser parser = new ArcaParser(TEST_TICKERS, inQ, outQ, collectionFactory);

        runParserThread(parser);

        //        make sure we didn't emit an extra record for the duplicate modify
        DataPoint one = outQ.deq();
        DataPoint two = outQ.deq();
        DataPoint three = outQ.deq();

        System.out.println("one: " + one.toString());
        System.out.println("two: " + two.toString());
        System.out.println("three: " + (three != null ? three.toString() : null));

        assertNotNull(one);
        assertNotNull(two);
        assertNull(three);

    }

    @Test
    public void testInstantiate() {
        try {

            @SuppressWarnings("UnusedDeclaration")
            ArcaParser parser = new ArcaParser(TEST_TICKERS,
                                               new WaitFreeQueue<String>(3),
                                               new WaitFreeQueue<DataPoint>(3),
                                               collectionFactory);
        } catch (Throwable t) {
            fail("Exception thrown during instantiation: " + t.toString());
        }
    }

    // ArcaParser buildParser(int capacity) {
    // 	return new ArcaParser(TEST_TICKERS,
    // 						  new WaitFreeQueue<String>(capacity),
    // 						  new WaitFreeQueue<DataPoint>(capacity));
    // }

    @Test
    public void testAddWhole() throws Exception {
        WaitFreeQueue<String> inQ = new WaitFreeQueue<>(5);
        WaitFreeQueue<DataPoint> outQ = new WaitFreeQueue<>(5);

        ArcaParser parser = new ArcaParser(TEST_TICKERS, inQ, outQ, collectionFactory);

        inQ.enq(TEST_WHOLE);
        runParserThread(parser);

        long[][] expectedOneBuy = {{275000000, 1000}};

        DataPoint buy1Expected = new ValidDataPoint("FOO", expectedOneBuy, new long[][]{}, 28800737000l, 1);

        assertTrue(buy1Expected.equals(outQ.deq()));

    }

    @Test
    public void testAdd() throws Exception {
        //		WaitFreeQueue<String> inQ = new WaitFreeQueue<>(5);
        //		WaitFreeQueue<DataPoint> outQ = new WaitFreeQueue<>(5);

        ArcaParser parser = this.parser;

        inQ.enq(TEST_ADD_BUY1);
        inQ.enq(TEST_ADD_SELL1);
        inQ.enq(TEST_ADD_BUY2);


        runParserThread(parser);


        long[][] expectedOneBuy = {{2750000, 1000}};


        long[][] expectedTwoBuy = {{2750000, 1000},
                // {980000, 3200}
        };
        long[][] expectedTwoSell = {{20000, 30000}};


        long[][] expectedThreeBuy = {{2750000, 1000}, {980000, 3200}};
        long[][] expectedThreeSell = {{20000, 30000}};


        DataPoint buy1Expected = new ValidDataPoint("FOO", expectedOneBuy, new long[][]{}, 28800737000l, 1);

        DataPoint sell1Expected = new ValidDataPoint("FOO", expectedTwoBuy, expectedTwoSell, 28800739000l, 9);

        DataPoint buy2Expected = new ValidDataPoint("FOO", expectedThreeBuy, expectedThreeSell, 28800737000l, 10);

        assertThat(outQ.deq(), equalTo(buy1Expected));
        assertThat(outQ.deq(), equalTo(sell1Expected));
        assertThat(outQ.deq(), equalTo(buy2Expected));
    }


    @Test
    public void testDuplicateAdd() throws Exception {
        assertThat(parser.getOrdersNow().keySet(), hasItem("FOO"));
        assertThat(parser.getOrdersNow().keySet(), hasItem("BAR"));

//        private final String TEST_ADD_SELL2 = "A,12,12884902091,B,S,200000,BAR,0.0195,28800,740,B,AARCA,";

        inQ.enq(TEST_ADD_BUY1);
        inQ.enq(TEST_ADD_BUY1_DUP);
        inQ.enq(TEST_ADD_SELL2);
        inQ.enq(TEST_ADD_BUY2);
        inQ.enq(TEST_ADD_BUY1);
        inQ.enq(TEST_MODIFY_BUY1);
        inQ.enq(TEST_DELETE_BUY1);


        runParserThread(parser);


        long[][] expectedOneBuy = {{2750000, 1000}};

        long[][] expectedSell = {{19500, 200000}};


        DataPoint buyExpected = new ValidDataPoint("FOO", expectedOneBuy, new long[][]{}, 28800737000l, 1);
        DataPoint poisonExpected = new PoisonDataPoint("FOO");
        DataPoint sellExpected = new ValidDataPoint("BAR", new long[][]{}, expectedSell, 28800740000l, 12);

        assertThat(outQ.deq(), equalTo(buyExpected));
        assertThat(outQ.deq(), equalTo(poisonExpected));
        assertThat(outQ.deq(), equalTo(sellExpected));
        assertThat(outQ.isEmpty(), is(true));

        assertThat(parser.getOrdersNow().keySet(), not(hasItem("Foo")));
        assertThat(parser.getOrdersNow().keySet(), hasItem("BAR"));
    }

    @Test
    public void testModify() throws Exception {
        WaitFreeQueue<String> inQ = new WaitFreeQueue<>(5);
        WaitFreeQueue<DataPoint> outQ = new WaitFreeQueue<>(5);

        ArcaParser parser = new ArcaParser(TEST_TICKERS, inQ, outQ, collectionFactory);

        inQ.enq(TEST_ADD_BUY1);
        inQ.enq(TEST_ADD_BUY2);
        inQ.enq(TEST_MODIFY_BUY1);
        inQ.enq(TEST_MODIFY_BUY2);

        runParserThread(parser);

        // throw away the first two, we don't care (add is tested elsewhere)
        outQ.deq();
        outQ.deq();

        long[][] expectedOrders1Buy = {{980000, 3200}, {382500, 900}};
        long[][] expectedEmptySell = {

        };

        long[][] expectedOrders2Buy = {{980000, 3000}, {382500, 900},};

        DataPoint expected1 = new ValidDataPoint("FOO", expectedOrders1Buy, expectedEmptySell, 29909390000l, 43);

        DataPoint expected2 = new ValidDataPoint("FOO", expectedOrders2Buy, expectedEmptySell, 33643922000l, 50);

        DataPoint toTest1 = outQ.deq();
        DataPoint toTest2 = outQ.deq();

        System.out.println("TO TEST #1:");
        toTest1.print();
        System.out.println("TO TEST #2:");
        toTest2.print();

        assertThat(toTest1, equalTo(expected1));
        assertThat(toTest2, equalTo(expected2));
    }

    @Test
    public void testPipelineError() throws InterruptedException {
        setParser(new MutableBoolean(true));

        ArcaParser parser = this.parser;

        inQ.enq(TEST_ADD_BUY1);
        inQ.enq(TEST_ADD_SELL1);
        inQ.enq(TEST_ADD_BUY2);

        runParserThread(parser);

        assertThat(outQ.isEmpty(), is(true));
    }

    @Test
    public void testNoIntegerPart() throws Exception {
        inQ.enq(TEST_ALL_DEC);
        runParserThread();

        DataPoint out = outQ.deq();

        assertNotNull(out);

        long[][] res = out.getBuy();
        long[] expected = new long[]{123456, 100};

        assertThat(res.length, is(1));

        Assert.assertArrayEquals(res[0], expected);
    }

    @Test
    public void testError() throws Exception {
        MutableBoolean errState = new MutableBoolean();
        setParser(errState);

        inQ.enq(TEST_ERRORS);

        runParserThread();

        assertThat(outQ.isEmpty(), is(true));
        assertThat(errState.booleanValue(), is(true));
    }
}
