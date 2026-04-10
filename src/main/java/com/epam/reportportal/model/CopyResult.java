package com.epam.reportportal.model;

public class CopyResult {

  private final boolean success;
  private final String errorMessage;
  private final long destSize;

  private CopyResult(boolean success, String errorMessage, long destSize) {
    this.success = success;
    this.errorMessage = errorMessage;
    this.destSize = destSize;
  }

  public static CopyResult success(long destSize) {
    return new CopyResult(true, null, destSize);
  }

  public static CopyResult failure(String error) {
    return new CopyResult(false, error, -1);
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
}
