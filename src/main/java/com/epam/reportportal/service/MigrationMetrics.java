package com.epam.reportportal.service;

import java.util.concurrent.atomic.AtomicLong;

public class MigrationMetrics {

  private final AtomicLong copied = new AtomicLong();
  private final AtomicLong skipped = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final AtomicLong retried = new AtomicLong();
  private final AtomicLong sourceNotFound = new AtomicLong();

  public void incrementCopied() {
    copied.incrementAndGet();
  }

  public void incrementSkipped() {
    skipped.incrementAndGet();
  }

  public void incrementFailed() {
    failed.incrementAndGet();
  }

  public void incrementRetried() {
    retried.incrementAndGet();
  }

  public void incrementSourceNotFound() {
    sourceNotFound.incrementAndGet();
  }

  public long getCopied() {
    return copied.get();
  }

  public long getSkipped() {
    return skipped.get();
  }

  public long getFailed() {
    return failed.get();
  }

  public long getRetried() {
    return retried.get();
  }

  public long getSourceNotFound() {
    return sourceNotFound.get();
  }

  public long getProcessed() {
    return copied.get() + skipped.get() + failed.get() + sourceNotFound.get();
  }

  public void mergeFrom(MigrationMetrics other) {
    copied.addAndGet(other.getCopied());
    skipped.addAndGet(other.getSkipped());
    failed.addAndGet(other.getFailed());
    retried.addAndGet(other.getRetried());
    sourceNotFound.addAndGet(other.getSourceNotFound());
  }

  public void reset() {
    copied.set(0);
    skipped.set(0);
    failed.set(0);
    retried.set(0);
    sourceNotFound.set(0);
  }

  public String summary() {
    return String.format(
        "copied=%d, skipped=%d, failed=%d, retried=%d, source_not_found=%d",
        getCopied(), getSkipped(), getFailed(), getRetried(), getSourceNotFound());
  }
}
