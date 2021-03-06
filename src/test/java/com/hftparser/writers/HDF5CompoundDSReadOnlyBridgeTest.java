package com.hftparser.writers;

import ch.systemsx.cisd.hdf5.IHDF5Writer;
import com.hftparser.data.DataSetName;
import com.hftparser.data.WritableDataPoint;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class HDF5CompoundDSReadOnlyBridgeTest {
    private final String TEST_PATH = "test-out.h5";
    private final DataSetName TEST_DS = new DataSetName("group", "foo");
    private HDF5CompoundDSReadOnlyBridge<WritableDataPoint> dtBridge;
    private IHDF5Writer writer;
    private WritableDataPoint testPoint1;
    private WritableDataPoint testPoint2;


    @Before
    public void setUp() throws Exception {
        try {
            File file = new File(TEST_PATH);

            testPoint1 = new WritableDataPoint(new long[][]{{1, 2}}, new long[][]{{3, 4}}, 6, 10l);
            testPoint2 = new WritableDataPoint(new long[][]{{4, 5}}, new long[][]{{6, 7}}, 7, 101l);

            writer = HDF5Writer.getWriter(file);

            HDF5CompoundDSBridgeBuilder<WritableDataPoint> dtBuilder = new HDF5CompoundDSBridgeBuilder<>(writer);
            dtBuilder.setTypeFromInferred(WritableDataPoint.class);

            dtBridge = dtBuilder.buildReadOnly(TEST_DS);

            HDF5CompoundDSBridge<WritableDataPoint> dtWriter = dtBuilder.build(TEST_DS);

            dtWriter.appendElement(testPoint1);
            dtWriter.appendElement(testPoint2);

        } catch (StackOverflowError e) {
            fail("This library has a bug in HDF5GenericStorageFeatures.java line 425, " +
                         "that throws it into an infinite loop if .defaultStorageLayout is called. NEVER, " +
                         "EVER CALL defaultStorageLayout.\n Failed with error: " + e.toString());
        }
    }

    @After
    public void tearDown() throws Exception {
        writer.close();
    }

    @Test
    public void testReadArray() throws Exception {
        WritableDataPoint[] blocks = dtBridge.readArray();

        assertEquals(testPoint1, blocks[0]);
        assertEquals(testPoint2, blocks[1]);
        assertEquals(blocks.length, 2);
    }
}