package org.hypertrace.entity.service.service;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;

import com.typesafe.config.ConfigFactory;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hypertrace.core.serviceframework.IntegrationTestServerUtil;
import org.hypertrace.entity.constants.v1.CommonAttribute;
import org.hypertrace.entity.data.service.client.EntityDataServiceClient;
import org.hypertrace.entity.data.service.v1.EntityRelationship;
import org.hypertrace.entity.data.service.v1.EntityRelationships;
import org.hypertrace.entity.service.client.config.EntityServiceClientConfig;
import org.hypertrace.entity.service.client.config.EntityServiceTestConfig;
import org.hypertrace.entity.service.constants.EntityConstants;
import org.hypertrace.entity.type.service.client.EntityTypeServiceClient;
import org.hypertrace.entity.type.service.v1.AttributeKind;
import org.hypertrace.entity.type.service.v1.AttributeType;
import org.hypertrace.entity.v1.entitytype.EntityType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Test case for testing relationships CRUD with {@link EntityDataServiceClient} */
public class EntityDataServiceRelationshipsTest {

  private static final Logger LOG =
      LoggerFactory.getLogger(EntityDataServiceRelationshipsTest.class);
  private static final Slf4jLogConsumer logConsumer = new Slf4jLogConsumer(LOG);

  private static EntityDataServiceClient entityDataServiceClient;
  private static final String TENANT_ID =
      "__testTenant__" + EntityDataServiceTest.class.getSimpleName();
  private static final int CONTAINER_STARTUP_ATTEMPTS = 5;

  private static GenericContainer<?> mongo;
  private static ManagedChannel channel;

  @BeforeAll
  public static void setUp() throws Exception {

    mongo =
        new GenericContainer<>(DockerImageName.parse("hypertrace/mongodb:main"))
            .withExposedPorts(27017)
            .withStartupAttempts(CONTAINER_STARTUP_ATTEMPTS)
            .waitingFor(Wait.forListeningPort())
            .withLogConsumer(logConsumer);
    mongo.start();

    withEnvironmentVariable("MONGO_HOST", mongo.getHost())
        .and("MONGO_PORT", mongo.getMappedPort(27017).toString())
        .execute(
            () -> {
              ConfigFactory.invalidateCaches();
              IntegrationTestServerUtil.startServices(new String[] {"entity-service"});
            });
    EntityServiceClientConfig entityServiceTestConfig = EntityServiceTestConfig.getClientConfig();
    channel =
        ManagedChannelBuilder.forAddress(
                entityServiceTestConfig.getHost(), entityServiceTestConfig.getPort())
            .usePlaintext()
            .build();

    entityDataServiceClient = new EntityDataServiceClient(channel);
    setupEntityTypes();
  }

  @AfterAll
  public static void teardown() {
    channel.shutdown();
    IntegrationTestServerUtil.shutdownServices();
    mongo.stop();
  }

  private static void setupEntityTypes() {
    EntityTypeServiceClient entityTypeServiceClient = new EntityTypeServiceClient(channel);
    entityTypeServiceClient.upsertEntityType(
        TENANT_ID,
        org.hypertrace.entity.type.service.v1.EntityType.newBuilder()
            .setName(EntityType.K8S_POD.name())
            .addAttributeType(
                AttributeType.newBuilder()
                    .setName(EntityConstants.getValue(CommonAttribute.COMMON_ATTRIBUTE_EXTERNAL_ID))
                    .setValueKind(AttributeKind.TYPE_STRING)
                    .setIdentifyingAttribute(true)
                    .build())
            .build());
    entityTypeServiceClient.upsertEntityType(
        TENANT_ID,
        org.hypertrace.entity.type.service.v1.EntityType.newBuilder()
            .setName(EntityType.DOCKER_CONTAINER.name())
            .addAttributeType(
                AttributeType.newBuilder()
                    .setName(EntityConstants.getValue(CommonAttribute.COMMON_ATTRIBUTE_EXTERNAL_ID))
                    .setValueKind(AttributeKind.TYPE_STRING)
                    .setIdentifyingAttribute(true)
                    .build())
            .build());
  }

  @Test
  public void testCreateAndGetEntityRelationship() {
    // Create two different relationships.
    EntityRelationship relationship1 =
        EntityRelationship.newBuilder()
            .setFromEntityId(UUID.randomUUID().toString())
            .setToEntityId(UUID.randomUUID().toString()) // container id
            .setEntityRelationshipType("POD_CONTAINER")
            .build();
    entityDataServiceClient.upsertRelationships(
        TENANT_ID, EntityRelationships.newBuilder().addRelationship(relationship1).build());

    EntityRelationship relationship2 =
        EntityRelationship.newBuilder()
            .setFromEntityId(UUID.randomUUID().toString()) // replicaset id
            .setToEntityId(relationship1.getFromEntityId())
            .setEntityRelationshipType("REPLICASET_POD")
            .build();
    entityDataServiceClient.upsertRelationships(
        TENANT_ID, EntityRelationships.newBuilder().addRelationship(relationship2).build());

    // Query all the relationships and verify.
    List<EntityRelationship> relationships = new ArrayList<>();
    entityDataServiceClient
        .getRelationships(TENANT_ID, null, null, null)
        .forEachRemaining(relationships::add);
    Assertions.assertEquals(2, relationships.size());

    // Query only one relationship and verify.
    relationships = new ArrayList<>();
    entityDataServiceClient
        .getRelationships(TENANT_ID, Set.of(relationship1.getEntityRelationshipType()), null, null)
        .forEachRemaining(relationships::add);
    Assertions.assertEquals(1, relationships.size());

    relationships = new ArrayList<>();
    entityDataServiceClient
        .getRelationships(TENANT_ID, Set.of(relationship2.getEntityRelationshipType()), null, null)
        .forEachRemaining(relationships::add);
    Assertions.assertEquals(1, relationships.size());

    relationships = new ArrayList<>();
    entityDataServiceClient
        .getRelationships(
            TENANT_ID, null, Collections.singleton(relationship1.getFromEntityId()), null)
        .forEachRemaining(relationships::add);
    Assertions.assertEquals(1, relationships.size());

    relationships = new ArrayList<>();
    entityDataServiceClient
        .getRelationships(
            TENANT_ID, null, null, Collections.singleton(relationship1.getToEntityId()))
        .forEachRemaining(relationships::add);
    Assertions.assertEquals(1, relationships.size());
  }
}
