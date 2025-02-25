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
package org.sonatype.nexus.blobstore.restore.orient;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.BlobStoreReconciliationLogger;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.blobstore.restore.RestoreBlobStrategy;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.logging.task.ProgressLogIntervalHelper;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maintenance.MaintenanceService;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.BucketStore;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.TaskSupport;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.time.LocalDate.now;
import static java.util.Objects.isNull;
import static java.util.Optional.ofNullable;
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.HEADER_PREFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.SINCE_DAYS;
import static org.sonatype.nexus.blobstore.restore.orient.DefaultOrientIntegrityCheckStrategy.DEFAULT_NAME;
import static org.sonatype.nexus.blobstore.restore.orient.OrientRestoreMetadataTaskDescriptor.BLOB_STORE_NAME_FIELD_ID;
import static org.sonatype.nexus.blobstore.restore.orient.OrientRestoreMetadataTaskDescriptor.DRY_RUN;
import static org.sonatype.nexus.blobstore.restore.orient.OrientRestoreMetadataTaskDescriptor.INTEGRITY_CHECK;
import static org.sonatype.nexus.blobstore.restore.orient.OrientRestoreMetadataTaskDescriptor.RESTORE_BLOBS;
import static org.sonatype.nexus.blobstore.restore.orient.OrientRestoreMetadataTaskDescriptor.UNDELETE_BLOBS;

/**
 * @since 3.4
 */
@Named
public class OrientRestoreMetadataTask
    extends TaskSupport
    implements Cancelable
{
  private final BlobStoreManager blobStoreManager;

  private final RepositoryManager repositoryManager;

  private final Map<String, RestoreBlobStrategy> restoreBlobStrategies;

  private final Map<String, OrientIntegrityCheckStrategy> integrityCheckStrategies;

  private final BlobStoreUsageChecker blobStoreUsageChecker;

  private final DryRunPrefix dryRunPrefix;

  private final OrientIntegrityCheckStrategy defaultOrientIntegrityCheckStrategy;

  private final BucketStore bucketStore;

  private final MaintenanceService maintenanceService;

  private final BlobStoreReconciliationLogger reconciliationLogger;

  @Inject
  public OrientRestoreMetadataTask(
      final BlobStoreManager blobStoreManager,
      final RepositoryManager repositoryManager,
      final Map<String, RestoreBlobStrategy> restoreBlobStrategies,
      final BlobStoreUsageChecker blobStoreUsageChecker,
      final DryRunPrefix dryRunPrefix,
      final Map<String, OrientIntegrityCheckStrategy> integrityCheckStrategies,
      final BucketStore bucketStore,
      final MaintenanceService maintenanceService,
      final BlobStoreReconciliationLogger reconciliationLogger)
  {
    this.blobStoreManager = checkNotNull(blobStoreManager);
    this.repositoryManager = checkNotNull(repositoryManager);
    this.restoreBlobStrategies = checkNotNull(restoreBlobStrategies);
    this.blobStoreUsageChecker = checkNotNull(blobStoreUsageChecker);
    this.dryRunPrefix = checkNotNull(dryRunPrefix);
    this.defaultOrientIntegrityCheckStrategy = checkNotNull(integrityCheckStrategies.get(DEFAULT_NAME));
    this.integrityCheckStrategies = checkNotNull(integrityCheckStrategies);
    this.bucketStore = checkNotNull(bucketStore);
    this.maintenanceService = checkNotNull(maintenanceService);
    this.reconciliationLogger = checkNotNull(reconciliationLogger);
  }

  @Override
  public String getMessage() {
    return null;
  }

  @Override
  protected Void execute() throws Exception {
    String blobStoreId = checkNotNull(getConfiguration().getString(BLOB_STORE_NAME_FIELD_ID));
    boolean dryRun = getConfiguration().getBoolean(DRY_RUN, false);
    boolean restoreBlobs = getConfiguration().getBoolean(RESTORE_BLOBS, false);
    boolean undeleteBlobs = getConfiguration().getBoolean(UNDELETE_BLOBS, false);
    boolean integrityCheck = getConfiguration().getBoolean(INTEGRITY_CHECK, false);
    Integer sinceDays = getConfiguration().getInteger(SINCE_DAYS, -1);

    restore(blobStoreId, restoreBlobs, undeleteBlobs, dryRun, sinceDays);

    blobStoreIntegrityCheck(integrityCheck, blobStoreId);

    return null;
  }

  private void restore(
      final String blobStoreName,
      final boolean restore,
      final boolean undelete,
      final boolean dryRun,
      final Integer sinceDays) // NOSONAR
  {
    if (!restore && !undelete) {
      log.warn("No repair/restore operations selected");
      return;
    }

    String logPrefix = dryRun ? dryRunPrefix.get() : "";
    BlobStore store = blobStoreManager.get(blobStoreName);

    long processed = 0;
    long undeleted = 0;
    boolean updateAssets = !dryRun && restore;
    Set<Repository> touchedRepositories = new HashSet<>();

    if (dryRun) {
      log.info("{}Actions will be logged, but no changes will be made.", logPrefix);
    }

    try (ProgressLogIntervalHelper progressLogger = new ProgressLogIntervalHelper(log, 60)) {
      for (BlobId blobId : (Iterable<BlobId>) getBlobIdStream(store, sinceDays)::iterator) {
        try {
          Optional<Context> context = buildContext(blobStoreName, store, blobId);
          if (context.isPresent()) {
            Context c = context.get();
            if (restore && c.restoreBlobStrategy != null && !c.blobAttributes.isDeleted()) {
              c.restoreBlobStrategy.restore(c.properties, c.blob, c.blobStore, dryRun);
            }
            if (undelete &&
                store.undelete(blobStoreUsageChecker, c.blobId, c.blobAttributes, dryRun)) {
              undeleted++;
            }

            if (updateAssets) {
              touchedRepositories.add(c.repository);
            }
          }

          processed++;

          progressLogger
              .info("{}Elapsed time: {}, processed: {}, un-deleted: {}", logPrefix, progressLogger.getElapsed(),
                  processed, undeleted);

          if (isCanceled()) {
            break;
          }
        }
        catch (Exception e) {
          log.error("Error restoring blob {}", blobId, e);
        }
      }
    }

    updateAssets(touchedRepositories, updateAssets);
  }

  private Stream<BlobId> getBlobIdStream(final BlobStore blobStore, final Integer sinceDays) {
    if (isNull(sinceDays) || sinceDays < 0) {
      log.info("Will process all blobs");
      return blobStore.getBlobIdStream();
    }
    else {
      LocalDate sinceDate = now().minusDays(sinceDays);
      log.info("Will process blobs created within last {} days, that is since {}", sinceDays, sinceDate);
      return reconciliationLogger.getBlobsCreatedSince(blobStore, sinceDate);
    }
  }

  private void updateAssets(final Set<Repository> repositories, final boolean updateAssets) {
    for (Repository repository : repositories) {
      if (isCanceled()) {
        break;
      }

      ofNullable(restoreBlobStrategies.get(repository.getFormat().getValue()))
          .ifPresent(strategy -> strategy.after(updateAssets, repository));
    }
  }

  private void blobStoreIntegrityCheck(final boolean integrityCheck, final String blobStoreId) {
    if (!integrityCheck) {
      log.warn("Integrity check operation not selected");
      return;
    }

    BlobStore blobStore = blobStoreManager.get(blobStoreId);

    if (blobStore == null) {
      log.error("Unable to find blob store '{}' in the blob store manager", blobStoreId);
      return;
    }

    StreamSupport.stream(repositoryManager.browseForBlobStore(blobStoreId).spliterator(), false)
        .filter(r -> !(r.getType() instanceof GroupType))
        .forEach(repository -> integrityCheckStrategies
            .getOrDefault(repository.getFormat().getValue(), defaultOrientIntegrityCheckStrategy)
            .check(repository, blobStore, this::isCanceled, this::integrityCheckFailedHandler)
        );
  }

  protected void integrityCheckFailedHandler(final Asset asset) {
    Bucket bucket = bucketStore.getById(asset.bucketId());
    Repository repository = repositoryManager.get(bucket.getRepositoryName());

    log.info("Removing asset {} from repository {}, blob integrity check failed", asset.name(), repository.getName());

    boolean dryRun = getConfiguration().getBoolean(DRY_RUN, false);
    if (!dryRun) {
      maintenanceService.deleteAsset(repository, asset);
    }
  }

  private Optional<Context> buildContext(final String blobStoreName, final BlobStore blobStore, final BlobId blobId)
  {
    return Optional.of(new Context(blobStoreName, blobStore, blobId))
        .map(context -> context.blob(context.blobStore.get(context.blobId, true)))
        .map(context -> context.blobAttributes(context.blobStore.getBlobAttributes(context.blobId)))
        .map(context -> context.properties(context.blobAttributes.getProperties()))
        .map(context -> context.repositoryName(context.properties.getProperty(HEADER_PREFIX + REPO_NAME_HEADER)))
        .map(context -> context.repository(repositoryManager.get(context.repositoryName)))
        .map(context -> context.restoreBlobStrategy(restoreBlobStrategies.get(context.repository.getFormat().getValue())));
  }

  private static class Context
  {
    final String blobStoreName;

    final BlobStore blobStore;

    final BlobId blobId;

    Blob blob;

    BlobAttributes blobAttributes;

    Properties properties;

    String repositoryName;

    Repository repository;

    RestoreBlobStrategy restoreBlobStrategy;

    Context(final String blobStoreName, final BlobStore blobStore, final BlobId blobId) {
      this.blobStoreName = checkNotNull(blobStoreName);
      this.blobStore = checkNotNull(blobStore);
      this.blobId = checkNotNull(blobId);
    }

    Context blob(final Blob blob) {
      if (blob == null) {
        return null;
      }
      else {
        this.blob = blob;
        return this;
      }
    }

    Context blobAttributes(final BlobAttributes blobAttributes) {
      if (blobAttributes == null) {
        return null;
      }
      else {
        this.blobAttributes = blobAttributes;
        return this;
      }
    }

    Context properties(final Properties properties) {
      if (properties == null) {
        return null;
      }
      else {
        this.properties = properties;
        return this;
      }
    }

    Context repositoryName(final String repositoryName) {
      if (repositoryName == null) {
        return null;
      }
      else {
        this.repositoryName = repositoryName;
        return this;
      }
    }

    Context repository(final Repository repository) {
      if (repository == null) {
        return null;
      }
      else {
        this.repository = repository;
        return this;
      }
    }

    Context restoreBlobStrategy(final RestoreBlobStrategy restoreBlobStrategy) {
      this.restoreBlobStrategy = restoreBlobStrategy;
      return this;
    }
  }
}
