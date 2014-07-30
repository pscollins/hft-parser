package com.hftparser.writers;

/**
 * Created by patrick on 7/28/14.
 */
class DatasetNameBuilder {
    private final String group;

    public DatasetNameBuilder(String group) {
        this.group = group;
    }

    public DatasetName build(String datasetName) {
        return new DatasetName(group, datasetName);
    }
}
