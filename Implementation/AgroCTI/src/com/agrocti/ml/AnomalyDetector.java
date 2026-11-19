package com.agrocti.ml;

import java.util.List;

/**
 * Common interface of every detector. The type parameter I is the kind of input:
 * a feature vector (double[]) or a sequence of readings (double[][]).
 * A higher score means "more unusual".
 */
public interface AnomalyDetector<I> {

    String name();

    /** Learns what normal data looks like. Only clean data is used (unsupervised). */
    void fit(List<I> normalData);

    double score(I input);

    /** Number of learned values the model keeps. */
    long parameterCount();

    /** Bytes of memory needed to run the model: parameters plus working buffers. */
    long memoryBytes();
}
