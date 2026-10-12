package com.timereport.timeentry.application.port.out;

import java.util.function.Supplier;

/** Execute validation and persistence atomically, serialized against all other service writes. */
public interface WriteTransaction {
    <T> T execute(Supplier<T> work);
}
