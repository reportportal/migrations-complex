package com.epam.reportportal.model;

public class CopyResult {

  private final boolean success;
  private final String errorMessage;
  private final long destSize;
  /** True when copy failed because the source object does not exist (e.g. S3 404). */
  private final boolean missingSource;

  private CopyResult(boolean success, String errorMessage, long destSize, boolean missingSource) {
    this.success = success;
    this.errorMessage = errorMessage;
    this.destSize = destSize;
    this.missingSource = missingSource;
  }

  public static CopyResult success(long destSize) {
    return new CopyResult(true, null, destSize, false);
  }

  public static CopyResult failure(String error) {
    return new CopyResult(false, error, -1, false);
  }

  /**
   * Copy could not run because the source key is missing. Callers should treat like HEAD miss:
   * SKIPPED / source_not_found, not a hard failure.
   */
  public static CopyResult missingSource() {
    return new CopyResult(false, "Source object not found", -1, true);
  }

  public boolean isSuccess() {
    return success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public long getDestSize() {
    return destSize;
  }

  public boolean isMissingSource() {
    return missingSource;
  }
}
