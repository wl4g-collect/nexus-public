/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.blobstore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.common.app.ApplicationDirectories;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.blobstore.DefaultBlobIdLocationResolver.TEMPORARY_BLOB_ID_PREFIX;

/**
 * Helper class for storing and retrieving reconciliation log for newly created blob store. Configuration is stored in
 * logback.xml. Logback will be responsible for rolling the files, and all we need to do is just call it via this class
 * that sets the context properties, so each blob store has its own reconciliation log file. Reconciliation logs are
 * stored at ${karaf.data}/log/blobstore/${blobstore}/%date.log
 */
@Singleton
public class BlobStoreReconciliationLogger
{
  public static final String BLOBSTORE = "blobstore";

  private static final String RECONCILIATION_LOGGER_NAME = "blobstore-reconciliation-log";

  private static final Logger LOGGER = LoggerFactory.getLogger(BlobStoreReconciliationLogger.class);

  private static final String BLOBSTORE_LOG_PATH = "log" + File.separator + BLOBSTORE + File.separator;

  private final Logger reconciliationLogger;

  private final ApplicationDirectories applicationDirectories;

  @Inject
  public BlobStoreReconciliationLogger(final ApplicationDirectories applicationDirectories) {
    this.applicationDirectories = checkNotNull(applicationDirectories);
    this.reconciliationLogger = LoggerFactory.getLogger(RECONCILIATION_LOGGER_NAME);
  }

  /**
   * Add new entry in rolling log later used in reconciliation task.
   *
   * @param blobStore in which new blob was created
   * @param blobId    of blob created
   */
  public void logBlobCreated(final BlobStore blobStore, final BlobId blobId) {
    if (isNotTemporaryBlob(blobId)) {
      MDC.put(BLOBSTORE, blobStore.getBlobStoreConfiguration().getName());
      reconciliationLogger.info(blobId.asUniqueString());
      MDC.remove(BLOBSTORE);
    }
  }

  private boolean isNotTemporaryBlob(final BlobId blobId) {
    return !blobId.asUniqueString().startsWith(TEMPORARY_BLOB_ID_PREFIX);
  }

  /**
   * Stream blob ids of blobs created in a blob store since specified date (inclusive).
   *
   * @param blobStore for which log will be retrieved
   * @param sinceDate for which retrieve newly created blob ids
   * @return stream of BlobId
   */
  public Stream<BlobId> getBlobsCreatedSince(final BlobStore blobStore, final LocalDate sinceDate) {
    String blobStoreName = blobStore.getBlobStoreConfiguration().getName();
    return getLogFilesToProcess(blobStoreName, sinceDate)
        .flatMap(this::readLines)
        .map(line -> {
          String[] split = line.split(",");
          if (split.length == 2) {
            return split[1];
          }
          else {
            LOGGER.info("Cannot find blob id on line, skipping: {}", line);
            return null;
          }
        })
        .filter(Objects::nonNull)
        .map(BlobId::new);
  }

  private Stream<String> readLines(final File file) {
    try {
      return Files.lines(file.toPath());
    }
    catch (IOException e) {
      LOGGER.error("Problem when reading file '{}'", file.getName(), e);
      return Stream.empty();
    }
  }

  private Stream<File> getLogFilesToProcess(final String blobStoreName, final LocalDate sinceDate) {
    String reconciliationLogPath = BLOBSTORE_LOG_PATH + blobStoreName;
    File reconciliationLogDirectory = applicationDirectories.getWorkDirectory(reconciliationLogPath);
    File[] logs = reconciliationLogDirectory.listFiles();
    if (Objects.nonNull(logs)) {
      return Stream.of(logs)
          .filter(isFileNameOlderOrSameAs(sinceDate))
          .peek(file -> LOGGER.info("Processing file '{}'", file.getName()));
    }
    else {
      LOGGER.info("No files found to process");
      return Stream.empty();
    }
  }

  private Predicate<File> isFileNameOlderOrSameAs(final LocalDate sinceDate) {
    return file -> {
      try {
        LocalDate logFileDate = LocalDate.parse(file.getName());
        return !sinceDate.isAfter(logFileDate);
      }
      catch (DateTimeParseException e) {
        return false;
      }
    };
  }
}
