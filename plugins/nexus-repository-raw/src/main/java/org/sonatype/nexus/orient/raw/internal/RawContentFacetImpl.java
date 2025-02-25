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
package org.sonatype.nexus.orient.raw.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.mime.MimeSupport;
import org.sonatype.nexus.orient.raw.RawContentFacet;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.raw.RawCoordinatesHelper;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.AssetBlob;
import org.sonatype.nexus.repository.storage.AssetEntityAdapter;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.Component;
import org.sonatype.nexus.repository.storage.ComponentMaintenance;
import org.sonatype.nexus.repository.storage.ReplicationFacet;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;
import org.sonatype.nexus.repository.transaction.TransactionalDeleteBlob;
import org.sonatype.nexus.repository.transaction.TransactionalStoreBlob;
import org.sonatype.nexus.repository.transaction.TransactionalStoreMetadata;
import org.sonatype.nexus.repository.transaction.TransactionalTouchBlob;
import org.sonatype.nexus.repository.transaction.TransactionalTouchMetadata;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.BlobPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.transaction.Transactional;
import org.sonatype.nexus.transaction.UnitOfWork;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import org.joda.time.DateTime;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.hash.Hashing.sha1;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.sonatype.nexus.common.hash.HashAlgorithm.MD5;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA1;
import static org.sonatype.nexus.repository.storage.MetadataNodeEntityAdapter.P_NAME;

/**
 * A {@link RawContentFacet} that persists to a {@link StorageFacet}.
 *
 * @since 3.0
 */
@Named
public class RawContentFacetImpl
    extends FacetSupport
    implements RawContentFacet, ReplicationFacet
{
  public static final List<HashAlgorithm> HASH_ALGORITHMS = ImmutableList.of(MD5, SHA1);

  private final AssetEntityAdapter assetEntityAdapter;

  private final MimeSupport mimeSupport;

  @Inject
  public RawContentFacetImpl(final AssetEntityAdapter assetEntityAdapter, final MimeSupport mimeSupport) {
    this.assetEntityAdapter = checkNotNull(assetEntityAdapter);
    this.mimeSupport = checkNotNull(mimeSupport);
  }

  // TODO: raw does not have config, this method is here only to have this bundle do Import-Package org.sonatype.nexus.repository.config
  // TODO: as FacetSupport subclass depends on it. Actually, this facet does not need any kind of configuration
  // TODO: it's here only to circumvent this OSGi/maven-bundle-plugin issue.
  @Override
  protected void doValidate(final Configuration configuration) throws Exception {
    // empty
  }

  @Nullable
  @Override
  @TransactionalTouchBlob
  public Content get(final String path) {
    StorageTx tx = UnitOfWork.currentTx();

    final Asset asset = findAsset(tx, path);
    if (asset == null) {
      return null;
    }

    final Blob blob = tx.requireBlob(asset.requireBlobRef());
    return toContent(asset, blob);
  }

  @Override
  public Content put(final String path, final Payload content) throws IOException {
    StorageFacet storageFacet = facet(StorageFacet.class);
    try (TempBlob tempBlob = storageFacet.createTempBlob(content, HASH_ALGORITHMS)) {
      return doPutContent(path, tempBlob, content);
    }
  }

  @Override
  @TransactionalStoreBlob
  public Asset put(final String path, final AssetBlob assetBlob, @Nullable final AttributesMap contentAttributes) {
    StorageTx tx = UnitOfWork.currentTx();
    Asset asset = getOrCreateAsset(getRepository(), path, RawCoordinatesHelper.getGroup(path), path);
    tx.attachBlob(asset, assetBlob);
    Content.applyToAsset(asset, Content.maintainLastModified(asset, contentAttributes));
    tx.saveAsset(asset);
    return asset;
  }

  @TransactionalStoreBlob
  protected Content doPutContent(final String path, final TempBlob tempBlob, final Payload payload)
      throws IOException
  {
    StorageTx tx = UnitOfWork.currentTx();

    Asset asset = getOrCreateAsset(getRepository(), path, RawCoordinatesHelper.getGroup(path), path);

    AttributesMap contentAttributes = null;
    if (payload instanceof Content) {
      contentAttributes = ((Content) payload).getAttributes();
    }
    Content.applyToAsset(asset, Content.maintainLastModified(asset, contentAttributes));
    AssetBlob assetBlob = tx.setBlob(
        asset,
        path,
        tempBlob,
        null,
        payload.getContentType(),
        false
    );

    tx.saveAsset(asset);

    return toContent(asset, assetBlob.getBlob());
  }

  @TransactionalStoreMetadata
  public Asset getOrCreateAsset(final Repository repository, final String componentName, final String componentGroup,
                                final String assetName) {
    final StorageTx tx = UnitOfWork.currentTx();

    final Bucket bucket = tx.findBucket(getRepository());
    Component component = tx.findComponentWithProperty(P_NAME, componentName, bucket);
    Asset asset;
    if (component == null) {
      // CREATE
      component = tx.createComponent(bucket, getRepository().getFormat())
          .group(componentGroup)
          .name(componentName);

      tx.saveComponent(component);

      asset = tx.createAsset(bucket, component);
      asset.name(assetName);
    }
    else {
      // UPDATE
      asset = tx.firstAsset(component);
      asset = asset != null ? asset : tx.createAsset(bucket, component).name(assetName);
    }

    return asset;
  }

  @Override
  @TransactionalDeleteBlob
  public boolean delete(final String path) throws IOException {
    StorageTx tx = UnitOfWork.currentTx();

    final Component component = findComponent(tx, tx.findBucket(getRepository()), path);
    if (component == null) {
      return false;
    }

    tx.deleteComponent(component);
    return true;
  }

  @Override
  @TransactionalTouchMetadata
  public void setCacheInfo(final String path, final Content content, final CacheInfo cacheInfo) throws IOException {
    StorageTx tx = UnitOfWork.currentTx();
    Bucket bucket = tx.findBucket(getRepository());

    // by EntityId
    Asset asset = Content.findAsset(tx, bucket, content);
    if (asset == null) {
      // by format coordinates
      Component component = tx.findComponentWithProperty(P_NAME, path, bucket);
      if (component != null) {
        asset = tx.firstAsset(component);
      }
    }
    if (asset == null) {
      log.debug("Attempting to set cache info for non-existent raw component {}", path);
      return;
    }

    log.debug("Updating cacheInfo of {} to {}", path, cacheInfo);
    CacheInfo.applyToAsset(asset, cacheInfo);
    tx.saveAsset(asset);
  }

  @Override
  @Transactional
  public boolean assetExists(final String name) {
    StorageTx tx = UnitOfWork.currentTx();
    return assetEntityAdapter.exists(tx.getDb(), name, tx.findBucket(getRepository()));
  }

  @Override
  @TransactionalStoreBlob
  public void hardLink(Repository repository, Asset asset, String path, Path contentPath) {
    StorageTx tx = UnitOfWork.currentTx();

    try {
      Map<HashAlgorithm, HashCode> hashes = singletonMap(SHA1, sha1().hashBytes(Files.readAllBytes(contentPath)));
      Map<String, String> headers = emptyMap();
      String contentType = mimeSupport.detectMimeType(Files.newInputStream(contentPath), path);
      long size = Files.size(contentPath);
      tx.setBlob(asset, path, contentPath, hashes, headers, contentType, size);

      Content.applyToAsset(asset, new AttributesMap(singletonMap(
          Content.CONTENT_LAST_MODIFIED, new DateTime(Files.getLastModifiedTime(contentPath).toMillis())
      )));
      tx.saveAsset(asset);
    }
    catch (IOException e) {
      log.error("Unable to hard link {} to {}", contentPath, path, e);
    }
  }

  private Component findComponent(StorageTx tx, Bucket bucket, String path) {
    return tx.findComponentWithProperty(P_NAME, path, bucket);
  }

  private Asset findAsset(StorageTx tx, String path) {
    return tx.findAssetWithProperty(P_NAME, path, tx.findBucket(getRepository()));
  }

  private Content toContent(final Asset asset, final Blob blob) {
    final Content content = new Content(new BlobPayload(blob, asset.requireContentType()));
    Content.extractFromAsset(asset, HASH_ALGORITHMS, content.getAttributes());
    return content;
  }

  @Override
  public void replicate(final String path, final AssetBlob assetBlob, @Nullable final NestedAttributesMap assetAttributes) {
    StorageFacet storageFacet = facet(StorageFacet.class);
    UnitOfWork.begin(storageFacet.txSupplier());
    try {
      putPreservingAllAttributes(path, assetBlob, assetAttributes);
    }
    finally {
      UnitOfWork.end();
    }
  }

  @TransactionalStoreBlob
  protected void putPreservingAllAttributes(final String path, final AssetBlob assetBlob, @Nullable final AttributesMap contentAttributes) {
    StorageTx tx = UnitOfWork.currentTx();
    Asset asset = getOrCreateAsset(getRepository(), path, RawCoordinatesHelper.getGroup(path), path);
    tx.attachBlob(asset, assetBlob);
    asset.attributes((NestedAttributesMap) contentAttributes);
    tx.saveAsset(asset);
  }

  @Override
  public boolean replicateDelete(final String path) {
    Asset asset;

    StorageFacet storageFacet = facet(StorageFacet.class);
    UnitOfWork.begin(storageFacet.txSupplier());
    try {
      asset = findAssetTransactional(path);
      if (asset == null) {
        log.debug("Skipping replication delete with asset {} as it doesn't exist.", path);
        return false;
      }
    }
    finally {
      UnitOfWork.end();
    }

    log.debug("Replicating delete to asset {}", path);
    return !getRepository().facet(ComponentMaintenance.class).deleteAsset(asset.getEntityMetadata().getId())
        .isEmpty();
  }

  @Transactional
  protected Asset findAssetTransactional(final String path) {
    StorageTx tx = UnitOfWork.currentTx();
    return findAsset(tx, path);
  }
}
