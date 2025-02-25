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
package org.sonatype.nexus.blobstore.s3.rest.internal;

import java.util.Optional;

import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.s3.internal.ui.S3Component;
import org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiModel;
import org.sonatype.nexus.rapture.PasswordPlaceholder;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.rest.WebApplicationMessageException;

import org.apache.commons.lang.StringUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static java.util.Optional.ofNullable;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CREATED;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.noContent;
import static javax.ws.rs.core.Response.ok;
import static javax.ws.rs.core.Response.status;
import static org.apache.commons.lang.StringUtils.equalsIgnoreCase;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.TYPE;
import static org.sonatype.nexus.blobstore.s3.rest.internal.S3BlobStoreApiConfigurationMapper.CONFIGURATION_MAPPER;
import static org.sonatype.nexus.blobstore.s3.rest.internal.S3BlobStoreApiModelMapper.MODEL_MAPPER;

/**
 * REST API endpoints for creating, reading, updating and deleting an S3 blob store.
 *
 * @since 3.20
 */
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class S3BlobStoreApiResource
    extends ComponentSupport
    implements Resource, S3BlobStoreApiResourceDoc
{
  private static final String UNKNOWN_BLOB_STORE_MSG_FORMAT = "\"Blob store %s doesn't exist.\"";

  private static final String NOT_AN_S3_BLOB_STORE_MSG_FORMAT = "\"%s is not an S3 blob store.\"";

  private final S3BlobStoreApiUpdateValidation s3BlobStoreApiUpdateValidation;

  private final BlobStoreManager blobStoreManager;

  private final S3Component s3Component = new S3Component();

  public S3BlobStoreApiResource(
      final BlobStoreManager blobStoreManager,
      final S3BlobStoreApiUpdateValidation validation)
  {
    this.blobStoreManager = blobStoreManager;
    this.s3BlobStoreApiUpdateValidation = validation;
  }

  @POST
  @Override
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:create")
  public Response createBlobStore(@Valid final S3BlobStoreApiModel request) throws Exception {
    try {
      final BlobStoreConfiguration blobStoreConfiguration =
          MODEL_MAPPER.apply(blobStoreManager.newConfiguration(), request);
      blobStoreManager.create(blobStoreConfiguration);
      return status(CREATED).build();
    }
    catch (Exception e) {
      throw new WebApplicationMessageException(INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @PUT
  @Override
  @RequiresAuthentication
  @Path("/{name}")
  @RequiresPermissions("nexus:blobstores:update")
  public void updateBlobStore(
      @Valid final S3BlobStoreApiModel request,
      @PathParam("name") final String blobStoreName) throws Exception
  {
    s3BlobStoreApiUpdateValidation.validateUpdateRequest(request, blobStoreName);

    if (isPasswordUntouched(request)) {
      //Did not update the password, just use the password we already have
      BlobStore currentS3Blobstore = blobStoreManager.get(blobStoreName);
      request.getBucketConfiguration().getBucketSecurity().setSecretAccessKey(
          currentS3Blobstore.getBlobStoreConfiguration().getAttributes().get("s3").get("secretAccessKey").toString());
    }

    try {
      final BlobStoreConfiguration blobStoreConfiguration =
          MODEL_MAPPER.apply(blobStoreManager.newConfiguration(), request);
      blobStoreManager.update(blobStoreConfiguration);
    }
    catch (Exception e) {
      throw new WebApplicationMessageException(INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  private boolean isPasswordUntouched(final S3BlobStoreApiModel request) {
    return request.getBucketConfiguration() != null && request.getBucketConfiguration().getBucketSecurity() != null &&
        PasswordPlaceholder.is(request.getBucketConfiguration().getBucketSecurity().getSecretAccessKey());
  }

  @GET
  @Override
  @RequiresAuthentication
  @Path("/{name}")
  @RequiresPermissions("nexus:blobstores:read")
  public S3BlobStoreApiModel getBlobStore(@PathParam("name") final String blobStoreName) {
    return fetchBlobStoreConfiguration(blobStoreName)
        .orElseThrow(() -> new WebApplicationMessageException(BAD_REQUEST,
            String.format(UNKNOWN_BLOB_STORE_MSG_FORMAT, blobStoreName), APPLICATION_JSON));
  }

  private Optional<S3BlobStoreApiModel> fetchBlobStoreConfiguration(final String blobStoreName) {
    Optional<S3BlobStoreApiModel> result = ofNullable(blobStoreManager.get(blobStoreName))
        .map(BlobStore::getBlobStoreConfiguration)
        .map(this::ensureBlobStoreTypeIsS3)
        .map(CONFIGURATION_MAPPER);
    if (result.isPresent() && isAuthenticationDataPresent(result.get())) {
      result.get().getBucketConfiguration().getBucketSecurity().setSecretAccessKey(PasswordPlaceholder.get());
    }
    return result;
  }

  private boolean isAuthenticationDataPresent(final S3BlobStoreApiModel s3BlobStoreApiModel) {
    return s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity() != null &&
        s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity().getAccessKeyId() != null &&
        isNotEmpty(s3BlobStoreApiModel.getBucketConfiguration().getBucketSecurity().getAccessKeyId());
  }

  private BlobStoreConfiguration ensureBlobStoreTypeIsS3(BlobStoreConfiguration configuration) {
    final String type = configuration.getType();
    if (!equalsIgnoreCase(TYPE, type)) {
      throw new WebApplicationMessageException(BAD_REQUEST,
          String.format(NOT_AN_S3_BLOB_STORE_MSG_FORMAT, configuration.getName()), APPLICATION_JSON);
    }
    return configuration;
  }
}
