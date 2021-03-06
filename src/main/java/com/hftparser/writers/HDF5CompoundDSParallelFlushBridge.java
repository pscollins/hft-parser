package com.hftparser.writers;

import ch.systemsx.cisd.hdf5.HDF5CompoundType;
import ch.systemsx.cisd.hdf5.IHDF5CompoundWriter;
import com.hftparser.config.HDF5CompoundDSBridgeConfig;
import com.hftparser.data.DataSetName;

import java.util.concurrent.ExecutorService;

/**
 * Created by patrick on 8/20/14.
 */
public class HDF5CompoundDSParallelFlushBridge<T> extends HDF5CompoundDSAsyncBridge<T> {
    public HDF5CompoundDSParallelFlushBridge(DataSetName name,
                                             HDF5CompoundType<T> type,
                                             IHDF5CompoundWriter writer,
                                             long startSize,
                                             int chunkSize,
                                             HDF5CompoundDSBridgeConfig bridgeConfig,
                                             ElementCacheFactory<T> cacheFactory,
                                             ExecutorService executor) {
        super(name, type, writer, startSize, chunkSize, bridgeConfig, cacheFactory, executor);
    }

    @Override
    public void prepareFlush() throws FailedWriteError {
        //        System.out.println("Forcing parallel prepareFlush");
        super.doFlush();

    }
}
