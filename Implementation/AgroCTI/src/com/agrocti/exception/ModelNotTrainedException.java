package com.agrocti.exception;

/** A detector was asked for a score before fit() was called. Unchecked: it is a programming error. */
public class ModelNotTrainedException extends IllegalStateException {
    public ModelNotTrainedException(String model) {
        super(model + " must be trained with fit() before it can score data");
    }
}
