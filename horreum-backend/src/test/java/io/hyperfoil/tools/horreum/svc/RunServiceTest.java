package io.hyperfoil.tools.horreum.svc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.horreum.api.SortDirection;
import io.hyperfoil.tools.horreum.api.alerting.ChangeDetection;
import io.hyperfoil.tools.horreum.api.alerting.Variable;
import io.hyperfoil.tools.horreum.api.internal.services.AlertingService;
import io.hyperfoil.tools.horreum.api.services.DatasetService;
import io.hyperfoil.tools.horreum.api.services.ExperimentService;
import io.hyperfoil.tools.horreum.api.services.RunService;
import io.hyperfoil.tools.horreum.bus.MessageBusChannels;
import io.hyperfoil.tools.horreum.mapper.DatasetMapper;
import jakarta.ws.rs.core.HttpHeaders;

import io.hyperfoil.tools.horreum.test.HorreumTestProfile;
import io.hyperfoil.tools.horreum.api.data.*;
import io.hyperfoil.tools.horreum.api.data.Extractor;
import io.hyperfoil.tools.horreum.entity.data.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.TestInfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.hyperfoil.tools.horreum.server.CloseMe;
import io.hyperfoil.tools.horreum.test.PostgresResource;
import io.hyperfoil.tools.horreum.test.TestUtil;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
@TestProfile(HorreumTestProfile.class)
public class RunServiceTest extends BaseServiceTest {
   private static final int POLL_DURATION_SECONDS = 10;

   @org.junit.jupiter.api.Test
   public void testTransformationNoSchemaInData(TestInfo info) throws InterruptedException {
      Test exampleTest = createExampleTest(getTestName(info));
      Test test = createTest(exampleTest);

      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);
      Extractor path = new Extractor("foo", "$.value", false);
      Schema schema = createExampleSchema(info);

      Transformer transformer = createTransformer("acme", schema, "", path);
      addTransformer(test, transformer);
      uploadRun("{\"corporation\":\"acme\"}", test.name);

      Dataset.EventNew event = dataSetQueue.poll(10, TimeUnit.SECONDS);
      assertNotNull(event);
      DatasetDAO dataset = DatasetDAO.findById(event.datasetId);
      TestUtil.assertEmptyArray(dataset.data);
   }

   @org.junit.jupiter.api.Test
   public void testTransformationWithoutSchema(TestInfo info) throws InterruptedException {
      Test test = createTest(createExampleTest(getTestName(info)));
      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);

      Schema schema = createExampleSchema(info);

      int runId = uploadRun(runWithValue(42, schema).toString(), test.name);

      assertNewDataset(dataSetQueue, runId);
      em.clear();

      BlockingQueue<Integer> trashedQueue = trashRun(runId);

      RunDAO run = RunDAO.findById(runId);
      assertNotNull(run);
      assertTrue(run.trashed);
      assertEquals(0, DatasetDAO.count());

      em.clear();

      // reinstate the run
      jsonRequest().post("/api/run/" + runId + "/trash?isTrashed=false").then().statusCode(204);
      assertNull(trashedQueue.poll(50, TimeUnit.MILLISECONDS));
      run = RunDAO.findById(runId);
      assertFalse(run.trashed);
      assertNewDataset(dataSetQueue, runId);
   }

   private void assertNewDataset(BlockingQueue<Dataset.EventNew> dataSetQueue, int runId) throws InterruptedException {
      Dataset.EventNew event = dataSetQueue.poll(10, TimeUnit.SECONDS);
      assertNotNull(event);
      assertNotNull(event.datasetId);
      assertEquals(runId, event.runId);
      DatasetDAO ds = DatasetDAO.findById(event.datasetId);
      assertNotNull(ds);
      assertEquals(runId, ds.run.id);
   }

   @org.junit.jupiter.api.Test
   public void testTransformationWithoutSchemaInUpload(TestInfo info) throws InterruptedException {
      Test test = createTest(createExampleTest(getTestName(info)));
      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);

      setTestVariables(test, "Value", "value");

      uploadRun( "{ \"foo\":\"bar\"}", test.name);

      Dataset.EventNew event = dataSetQueue.poll(10, TimeUnit.SECONDS);
      assertNotNull(event);
      DatasetDAO ds = DatasetDAO.findById(event.datasetId);
      TestUtil.assertEmptyArray(ds.data);
   }

   @org.junit.jupiter.api.Test
   public void testTransformationWithoutExtractorsAndBlankFunction(TestInfo info) throws InterruptedException {
      Test exampleTest = createExampleTest(getTestName(info));
      Test test = createTest(exampleTest);

      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);
      Schema schema = createExampleSchema(info);

      Transformer transformer = createTransformer("acme", schema, "");
      addTransformer(test, transformer);
      uploadRun(runWithValue(42.0d, schema), test.name);

      Dataset.EventNew event = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);
      assertNotNull(event);
      DatasetDAO ds = DatasetDAO.findById(event.datasetId);
      JsonNode node = ds.data;
      assertTrue(node.isArray());
      assertEquals(1, node.size());
      assertEquals(1, node.get(0).size());
      assertTrue(node.get(0).hasNonNull("$schema"));
   }

   @org.junit.jupiter.api.Test
   public void testTransformationWithExtractorAndBlankFunction(TestInfo info) throws InterruptedException {
      Test exampleTest = createExampleTest(getTestName(info));
      Test test = createTest(exampleTest);

      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);
      Schema schema = createExampleSchema("AcneCorp", "AcneInc", "AcneRrUs", false);

      Extractor path = new Extractor("foo", "$.value", false);
      Transformer transformer = createTransformer("acme", schema, "", path); // blank function
      addTransformer(test, transformer);
      uploadRun(runWithValue(42.0d, schema), test.name);

      Dataset.EventNew event = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);
      assertNotNull(event);
      DatasetDAO ds = DatasetDAO.findById(event.datasetId);
      assertTrue(ds.data.isArray());
      assertEquals(1, ds.data.size());
      // the result of single extractor is 42, hence this needs to be wrapped into an object (using `value`) before adding schema
      assertEquals(42, ds.data.path(0).path("value").intValue());
   }

   @org.junit.jupiter.api.Test
   public void testTransformationWithNestedSchema(TestInfo info) throws InterruptedException {
      Schema acmeSchema = createExampleSchema("AcmeCorp", "AcmeInc", "AcmeRrUs", false);
      Schema roadRunnerSchema = createExampleSchema("RoadRunnerCorp", "RoadRunnerInc", "RoadRunnerRrUs", false);

      Extractor acmePath = new Extractor("foo", "$.value", false);
      Transformer acmeTransformer = createTransformer("acme", acmeSchema, "value => ({ acme: value })", acmePath);
      Extractor roadRunnerPath = new Extractor("bah", "$.value", false);
      Transformer roadRunnerTransformer = createTransformer("roadrunner", roadRunnerSchema, "value => ({ outcome: value })", roadRunnerPath);

      Test exampleTest = createExampleTest(getTestName(info));
      Test test = createTest(exampleTest);
      addTransformer(test, acmeTransformer, roadRunnerTransformer);

      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);

      String data = runWithValue(42.0d, acmeSchema, roadRunnerSchema).toString();
      int runId = uploadRun(data, test.name);

      Dataset.EventNew event = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);

      assertNotNull(event);
      DatasetDAO ds = DatasetDAO.findById(event.datasetId);
      JsonNode node = ds.data;
      assertTrue(node.isArray());
      assertEquals(2 , node.size());
      validate("42", node.path(0).path("acme"));
      validate("42", node.path(1).path("outcome"));
      RunDAO run = RunDAO.findById(runId);
      assertEquals(1, run.datasets.size());
   }

   @org.junit.jupiter.api.Test
   public void testTransformationSingleSchemaTestWithoutTransformer(TestInfo info) throws InterruptedException {
      Test exampleTest = createExampleTest(getTestName(info));
      Test test = createTest(exampleTest);

      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);
      Schema acmeSchema = createExampleSchema("AceCorp", "AceInc", "AceRrUs", false);

      uploadRun(runWithValue(42.0d, acmeSchema), test.name);

      Dataset.EventNew event = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);

      assertNotNull(event);
      DatasetDAO ds = DatasetDAO.findById(event.datasetId);
      JsonNode node = ds.data;
      assertTrue(node.isArray());
      ObjectNode object = (ObjectNode)node.path(0);
      JsonNode schema = object.path("$schema");
      assertEquals("urn:AceInc:AceRrUs:1.0", schema.textValue());
      JsonNode value = object.path("value");
      assertEquals(42, value.intValue());
   }

   @org.junit.jupiter.api.Test
   public void testTransformationNestedSchemasWithoutTransformers(TestInfo info) throws InterruptedException {
      Test test = createTest(createExampleTest(getTestName(info)));
      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);
      Schema schemaA = createExampleSchema("Ada", "Ada", "Ada", false);
      Schema schemaB = createExampleSchema("Bdb", "Bdb", "Bdb", false);
      Schema schemaC = createExampleSchema("Cdc", "Cdc", "Cdc", false);

      ObjectNode data = runWithValue(1, schemaA);
      data.set("nestedB", runWithValue(2, schemaB));
      data.set("nestedC", runWithValue(3, schemaC));
      uploadRun(data, test.name);

      Dataset.EventNew event = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);

      assertNotNull(event);
      Dataset dataset = DatasetMapper.from( DatasetDAO.findById(event.datasetId));
      assertTrue(dataset.data.isArray());
      assertEquals(3, dataset.data.size());
      assertEquals(1, getBySchema(dataset, schemaA).path("value").intValue());
      assertEquals(2, getBySchema(dataset, schemaB).path("value").intValue());
      assertEquals(3, getBySchema(dataset, schemaC).path("value").intValue());

      assertNull(dataSetQueue.poll(50, TimeUnit.MILLISECONDS));
   }

   private JsonNode getBySchema(Dataset dataset, Schema schemaA) {
      return StreamSupport.stream(dataset.data.spliterator(), false)
            .filter(item -> schemaA.uri.equals(item.path("$schema").textValue()))
            .findFirst().orElseThrow(AssertionError::new);
   }

   @org.junit.jupiter.api.Test
   public void testTransformationUsingSameSchemaInBothLevelsTestWithoutTransformer(TestInfo info) throws InterruptedException {
      Test exampleTest = createExampleTest(getTestName(info));
      Test test = createTest(exampleTest);

      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);

      Schema appleSchema = createExampleSchema("AppleCorp", "AppleInc", "AppleRrUs", false);

      ObjectNode data = runWithValue(42.0d, appleSchema);
      ObjectNode nested = runWithValue(52.0d, appleSchema);
      data.set("field_" + appleSchema.name, nested);

      uploadRun(data, test.name);

      Dataset.EventNew event = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);

      assertNotNull(event);
      DatasetDAO ds = DatasetDAO.findById(event.datasetId);
      JsonNode node = ds.data;
      assertTrue(node.isArray());
      assertEquals(2 , node.size());

      JsonNode first = node.path(0);
      assertEquals("urn:AppleInc:AppleRrUs:1.0", first.path("$schema").textValue());
      assertEquals(42, first.path("value").intValue());

      JsonNode second = node.path(1);
      assertEquals("urn:AppleInc:AppleRrUs:1.0", second.path("$schema").textValue());
      assertEquals(52, second.path("value").intValue());
   }

   @org.junit.jupiter.api.Test
    public void testTransformationUsingSingleSchemaTransformersProcessScalarPlusArray(TestInfo info) throws InterruptedException {
      Schema schema = createExampleSchema("ArrayCorp", "ArrayInc", "ArrayRrUs", false);
      Extractor arrayPath = new Extractor("mheep", "$.values", false);
      String arrayFunction = "mheep => { return mheep.map(x => ({ \"outcome\": x }))}";

      Extractor scalarPath = new Extractor("sheep", "$.value", false);
      String scalarFunction = "sheep => { return ({  \"outcome\": { sheep } }) }";

      Transformer arrayTransformer = createTransformer("arrayT", schema, arrayFunction, arrayPath);
      Transformer scalarTransformer = createTransformer("scalarT", schema, scalarFunction, scalarPath);

      Test exampleTest = createExampleTest(getTestName(info));
      Test test = createTest(exampleTest);
      addTransformer(test, arrayTransformer, scalarTransformer);

      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);

      ObjectNode data = runWithValue(42.0d, schema);

      uploadRun(data,test.name);

      Dataset.EventNew first = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);
      Dataset.EventNew second = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);
      Dataset.EventNew third = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);

      assertNotNull(first);
      assertNotNull(second);
      assertNotNull(third);
      Dataset ds = DatasetMapper.from( DatasetDAO.findById(first.datasetId));
      assertTrue(ds.data.isArray());
      String target = postFunctionSchemaUri(schema);
      validateScalarArray(ds, target);
      ds = DatasetMapper.from( DatasetDAO.findById(second.datasetId));
      validateScalarArray(ds, target);
      ds = DatasetMapper.from( DatasetDAO.findById(third.datasetId));
      validateScalarArray(ds, target);
   }

   @org.junit.jupiter.api.Test
   public void testSelectRunBySchema(TestInfo info) throws InterruptedException {
      Schema schemaA = createExampleSchema("Aba", "Aba", "Aba", false);
      Test test = createTest(createExampleTest(getTestName(info)));

      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);

      uploadRun(runWithValue(42, schemaA), test.name);
      Dataset.EventNew event = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);
      assertNotNull(event);
      assertNull(dataSetQueue.poll(50, TimeUnit.MILLISECONDS));

      RunService.RunsSummary runsSummary = jsonRequest()
              .get("/api/run/bySchema?uri=" + schemaA.uri)
              .then()
              .statusCode(200)
              .extract().body().as(RunService.RunsSummary.class);

      assertNotNull(runsSummary);
      assertEquals(1, runsSummary.total);
   }
   @org.junit.jupiter.api.Test
   public void testTransformationChoosingSchema(TestInfo info) throws InterruptedException {
      Schema schemaA = createExampleSchema("Aba", "Aba", "Aba", false);
      Extractor path = new Extractor("value", "$.value", false);
      Transformer transformerA = createTransformer("A", schemaA, "value => ({\"by\": \"A\"})", path);

      Schema schemaB = createExampleSchema("Bcb", "Bcb", "Bcb", false);
      Transformer transformerB = createTransformer("B", schemaB, "value => ({\"by\": \"B\"})");

      Test test = createTest(createExampleTest(getTestName(info)));
      addTransformer(test, transformerA, transformerB);

      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);

      uploadRun(runWithValue(42, schemaB), test.name);
      Dataset.EventNew event = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);
      assertNotNull(event);
      Dataset dataset = DatasetMapper.from( DatasetDAO.findById(event.datasetId));
      assertTrue(dataset.data.isArray());
      assertEquals(1, dataset.data.size());
      assertEquals("B", dataset.data.get(0).path("by").asText());

      assertNull(dataSetQueue.poll(50, TimeUnit.MILLISECONDS));
   }

   @org.junit.jupiter.api.Test
   public void testTransformationWithoutMatchFirstLevel(TestInfo info) throws InterruptedException {
      Schema schema = createExampleSchema("Aca", "Aca", "Aca", false);
      testTransformationWithoutMatch(info, schema, runWithValue(42, schema));
   }

   @org.junit.jupiter.api.Test
   public void testTransformationWithoutMatchSecondLevel(TestInfo info) throws InterruptedException {
      Schema schema = createExampleSchema("B", "B", "B", false);
      testTransformationWithoutMatch(info, schema, JsonNodeFactory.instance.objectNode().set("nested", runWithValue(42, schema)));
   }

   @org.junit.jupiter.api.Test
   public void testSchemaTransformerWithExtractorProducingNullValue(TestInfo info) throws InterruptedException {
      Schema schema = createExampleSchema("DDDD", "DDDDInc", "DDDDRrUs", true);
      Extractor scalarPath = new Extractor("sheep", "$.duff", false);
      Transformer scalarTransformer = createTransformer("tranProcessNullExtractorValue", schema, "sheep => ({ outcome: { sheep }})", scalarPath);

      Test exampleTest = createExampleTest(getTestName(info));
      Test test = createTest(exampleTest);
      addTransformer(test, scalarTransformer);

      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);

      ObjectNode data = runWithValue(42.0d, schema);

      uploadRun(data,test.name);

      Dataset.EventNew event = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);
      assertNotNull(event);
      Dataset dataset = DatasetMapper.from( DatasetDAO.findById(event.datasetId));
      JsonNode eventData = dataset.data;
      assertTrue(eventData.isArray());
      assertEquals(1, eventData.size());
      JsonNode sheep = eventData.path(0).path("outcome").path("sheep");
      assertTrue(sheep.isEmpty());
   }

   private void testTransformationWithoutMatch(TestInfo info, Schema schema, ObjectNode data) throws InterruptedException {
      Extractor firstMatch = new Extractor("foo", "$.foo", false);
      Extractor allMatches = new Extractor("bar", "$.bar[*].x", false);
      allMatches.array = true;
      Extractor value = new Extractor("value", "$.value", false);
      Extractor values = new Extractor("values", "$.values[*]", false);
      values.array = true;

      Transformer transformerNoFunc = createTransformer("noFunc", schema, null, firstMatch, allMatches);
      Transformer transformerFunc = createTransformer("func", schema, "({foo, bar}) => ({ foo, bar })", firstMatch, allMatches);
      Transformer transformerCombined = createTransformer("combined", schema, null, firstMatch, allMatches, value, values);

      Test test = createTest(createExampleTest(getTestName(info)));
      addTransformer(test, transformerNoFunc, transformerFunc, transformerCombined);
      BlockingQueue<Dataset.EventNew> dataSetQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);

      uploadRun(data, test.name);
      Dataset.EventNew event = dataSetQueue.poll(POLL_DURATION_SECONDS, TimeUnit.SECONDS);

      assertNotNull(event);
      Dataset dataset = DatasetMapper.from( DatasetDAO.findById(event.datasetId));
      assertTrue(dataset.data.isArray());
      assertEquals(3, dataset.data.size());
      dataset.data.forEach(item -> {
         assertTrue(item.path("foo").isNull());
         TestUtil.assertEmptyArray(item.path("bar"));
      });

      JsonNode combined = dataset.data.get(2);
      assertEquals(42, combined.path("value").intValue());
      assertTrue(combined.path("values").isArray());
      assertEquals(3, combined.path("values").size());
   }

   private void validate(String expected, JsonNode node) {
      assertNotNull(node);
      assertFalse(node.isMissingNode());
      assertEquals(expected, node.asText());
   }

   @org.junit.jupiter.api.Test
   public void testRecalculateDatasets() throws InterruptedException {
      withExampleDataset(createTest(createExampleTest("dummy")), JsonNodeFactory.instance.objectNode(), ds -> {
         Util.withTx(tm, () -> {
            try (CloseMe ignored = roleManager.withRoles(SYSTEM_ROLES)) {
               DatasetDAO dbDs = DatasetDAO.findById(ds.id);
               assertNotNull(dbDs);
               dbDs.delete();
               em.flush();
               em.clear();
            }
            return null;
         });
         List<Integer> dsIds1 = recalculateDataset(ds.runId);
         assertEquals(1, dsIds1.size());
         try (CloseMe ignored = roleManager.withRoles(SYSTEM_ROLES)) {
            List<DatasetDAO> dataSets = DatasetDAO.find("run.id", ds.runId).list();
            assertEquals(1, dataSets.size());
            assertEquals(dsIds1.get(0), dataSets.get(0).id);
            em.clear();
         }
         List<Integer> dsIds2 = recalculateDataset(ds.runId);
         try (CloseMe ignored = roleManager.withRoles(SYSTEM_ROLES)) {
            List<DatasetDAO> dataSets = DatasetDAO.find("run.id", ds.runId).list();
            assertEquals(1, dataSets.size());
            assertEquals(dsIds2.get(0), dataSets.get(0).id);
         }
         return null;
      });
   }

   protected List<Integer> recalculateDataset(int runId) {
      ArrayNode json = jsonRequest().post("/api/run/" + runId + "/recalculate").then().statusCode(200).extract().body().as(ArrayNode.class);
      ArrayList<Integer> list = new ArrayList<>(json.size());
      json.forEach(item -> list.add(item.asInt()));
      return list;
   }

   private void validateScalarArray(Dataset ds, String expectedTarget) {
      JsonNode n = ds.data;
      int outcome = n.path(0).findValue("outcome").asInt();
      assertTrue(outcome == 43 || outcome == 44 || outcome == 45 );
      int value = n.path(1).path("outcome").path("sheep").asInt();
      assertEquals(42, value);
      String scalarTarget = n.path(0).path("$schema").textValue();
      assertEquals(expectedTarget, scalarTarget);
      String arrayTarget = n.path(1).path("$schema").textValue();
      assertEquals(expectedTarget, arrayTarget);
   }

   @org.junit.jupiter.api.Test
   public void testUploadToPrivateTest() throws JsonProcessingException {
      Test test = createExampleTest("supersecret");
      test.access = Access.PRIVATE;
      test = createTest(test);

      JsonNode payload = new ObjectMapper().readTree(resourceToString("data/config-quickstart.jvm.json"));
      long now = System.currentTimeMillis();
      int runID = uploadRun(now, now, payload, test.name, test.owner, Access.PRIVATE);

      RunService.RunExtended response = RestAssured.given().auth().oauth2(getTesterToken())
              .header(HttpHeaders.CONTENT_TYPE, "application/json")
              .body(org.testcontainers.shaded.com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode())
              .get("/api/run/" + runID)
              .then()
              .statusCode(200)
              .extract().as(RunService.RunExtended.class);
      assertNotNull(response);
      assertEquals(test.name, response.testname);
   }

   @org.junit.jupiter.api.Test
   public void testUploadToPrivateUsingToken() {
      final String MY_SECRET_TOKEN = "mySecretToken";
      Test test = createExampleTest("supersecret");
      test.access = Access.PRIVATE;
      test = createTest(test);

      // TestToken.value is not readable, therefore we can't pass it in.
      addToken(test, TestTokenDAO.READ + TestTokenDAO.UPLOAD, MY_SECRET_TOKEN);

      long now = System.currentTimeMillis();
      RestAssured.given()
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .body(org.testcontainers.shaded.com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode())
            .post("/api/run/data?start=" + now + "&stop=" + now + "&test=" + test.name + "&owner=" + UPLOADER_ROLES[0] +
                  "&access=" + Access.PRIVATE + "&token=" + MY_SECRET_TOKEN)
            .then()
            .statusCode(200)
            .extract().asString();
   }

   @org.junit.jupiter.api.Test
   public void testRetrieveData() {
      Test test = createTest(createExampleTest("dummy"));
      Schema schemaA = createExampleSchema("A", "A", "A", false);
      Schema schemaB = createExampleSchema("B", "B", "B", false);

      ObjectNode data1 = JsonNodeFactory.instance.objectNode()
            .put("$schema", schemaA.uri).put("value", 42);
      int run1 = uploadRun(data1, test.name);

      JsonNode data1Full = getData(run1, null);
      assertEquals(data1, data1Full);
      JsonNode data1A = getData(run1, schemaA);
      assertEquals(data1, data1A);

      ArrayNode data2 = JsonNodeFactory.instance.arrayNode();
      data2.addObject().put("$schema", schemaA.uri).put("value", 43);
      data2.addObject().put("$schema", schemaB.uri).put("value", 44);
      int run2 = uploadRun(data2, test.name);

      JsonNode data2Full = getData(run2, null);
      assertEquals(data2, data2Full);
      JsonNode data2A = getData(run2, schemaA);
      assertEquals(data2.get(0), data2A);
      JsonNode data2B = getData(run2, schemaB);
      assertEquals(data2.get(1), data2B);

      ObjectNode data3 = JsonNodeFactory.instance.objectNode();
      data3.putObject("foo").put("$schema", schemaA.uri).put("value", 45);
      data3.putObject("bar").put("$schema", schemaB.uri).put("value", 46);
      int run3 = uploadRun(data3, test.name);

      JsonNode data3Full = getData(run3, null);
      assertEquals(data3, data3Full);
      JsonNode data3A = getData(run3, schemaA);
      assertEquals(data3.get("foo"), data3A);
      JsonNode data3B = getData(run3, schemaB);
      assertEquals(data3.get("bar"), data3B);
   }

   @org.junit.jupiter.api.Test
   public void testUploadWithMetadata() throws InterruptedException {
      Test test = createTest(createExampleTest("with_meta"));
      createSchema("Foo", "urn:foo");
      createSchema("Bar", "urn:bar");
      createSchema("Q", "urn:q");
      Schema gooSchema = createSchema("Goo", "urn:goo");
      Transformer transformer = createTransformer("ttt", gooSchema, "goo => ({ oog: goo })", new Extractor("goo", "$.goo", false));
      addTransformer(test, transformer);
      Schema postSchema = createSchema("Post", "uri:Goo-post-function");

      long now = System.currentTimeMillis();
      ObjectNode data = simpleObject("urn:foo", "foo", "xxx");
      ArrayNode metadata = JsonNodeFactory.instance.arrayNode();
      metadata.add(simpleObject("urn:bar", "bar", "yyy"));
      metadata.add(simpleObject("urn:goo", "goo", "zzz"));

      BlockingQueue<Dataset.EventNew> dsQueue = eventConsumerQueue(Dataset.EventNew.class, MessageBusChannels.DATASET_NEW, e -> e.testId == test.id);

      int run1 = uploadRun(now, data, metadata, test.name);

      Dataset.EventNew event1 = dsQueue.poll(10, TimeUnit.SECONDS);
      assertNotNull(event1);
      assertEquals(run1, event1.runId);
      DatasetDAO dataset = DatasetDAO.findById(event1.datasetId);
      assertEquals(3, dataset.data.size());
      JsonNode foo = getBySchema(dataset.data, "urn:foo");
      assertEquals("xxx", foo.path("foo").asText());
      JsonNode bar = getBySchema(dataset.data, "urn:bar");
      assertEquals("yyy", bar.path("bar").asText());
      JsonNode goo = getBySchema(dataset.data, postSchema.uri);
      assertEquals("zzz", goo.path("oog").asText());

      // test auto-wrapping of object metadata into array
      int run2 = uploadRun(now + 1, data, simpleObject("urn:q", "qqq", "xxx"), test.name);
      Dataset.EventNew event2 = dsQueue.poll(10, TimeUnit.SECONDS);
      assertNotNull(event2);
      assertEquals(run2, event2.runId);
      dataset = DatasetDAO.findById(event2.datasetId);
      assertEquals(2, dataset.data.size());
      JsonNode qqq = getBySchema(dataset.data, "urn:q");
      assertEquals("xxx", qqq.path("qqq").asText());
   }

   @org.junit.jupiter.api.Test
   public void testListAllRuns() throws IOException {
      Test test = createTest(createExampleTest("with_meta"));
      createSchema("Foo", "urn:foo");
      createSchema("Bar", "urn:bar");
      createSchema("Q", "urn:q");
      Schema gooSchema = createSchema("Goo", "urn:goo");
      Transformer transformer = createTransformer("ttt", gooSchema, "goo => ({ oog: goo })", new Extractor("goo", "$.goo", false));
      addTransformer(test, transformer);
      Schema postSchema = createSchema("Post", "uri:Goo-post-function");

      long now = System.currentTimeMillis();
      ObjectNode data = simpleObject("urn:foo", "foo", "xxx");
      ArrayNode metadata = JsonNodeFactory.instance.arrayNode();
      metadata.add(simpleObject("urn:bar", "bar", "yyy"));
      metadata.add(simpleObject("urn:goo", "goo", "zzz"));

      int run1 = uploadRun(now, data, metadata, test.name);

      RunService.RunsSummary runs = jsonRequest()
            .get("/api/run/list?limit=10&page=1&query=$.*")
              .then()
              .statusCode(200)
              .extract()
              .as(RunService.RunsSummary.class);

      assertEquals(1, runs.runs.size());
      assertEquals(test.name, runs.runs.get(0).testname);
   }

   @org.junit.jupiter.api.Test
   public void testListAllRunsFromFiles() throws IOException {
      populateDataFromFiles();

      RunService.RunsSummary runs = jsonRequest()
              .get("/api/run/list?limit=10&page=1&"+
                      "query=$.buildHash ? (@ == \"defec8eddeadbeafcafebabeb16b00b5\")"
              )
              .then()
              .statusCode(200)
              .extract()
              .as(RunService.RunsSummary.class);

      assertEquals(1, runs.runs.size());
   }

   @org.junit.jupiter.api.Test
   public void testAddRunFromData() throws JsonProcessingException {
      Test test = createExampleTest("supersecret");
      test.access = Access.PRIVATE;
      test = createTest(test);

      JsonNode payload = new ObjectMapper().readTree(resourceToString("data/config-quickstart.jvm.json"));

      int runId = uploadRun("$.start", "$.stop", test.name, test.owner, Access.PUBLIC,
              null, null, "test", payload);
      assertTrue(runId > 0);
   }
   @org.junit.jupiter.api.Test
   public void testAddRunWithMetadataData() throws JsonProcessingException {
      Test test = createExampleTest("supersecret");
      test.access = Access.PRIVATE;
      test = createTest(test);

      JsonNode payload = new ObjectMapper().readTree(resourceToString("data/config-quickstart.jvm.json"));
      JsonNode metadata = JsonNodeFactory.instance.objectNode().put("$schema", "urn:foobar").put("foo", "bar");

      int runId = uploadRun("$.start", "$.stop", payload, metadata, test.name, test.owner, Access.PUBLIC);
      assertTrue(runId > 0);
   }
   @org.junit.jupiter.api.Test
   public void testJavascriptExecution() throws InterruptedException {
      Test test = createExampleTest("supersecret");
      test = createTest(test);

      Schema schema = new Schema();
      schema.uri = "urn:dummy:schema";
      schema.name = "Dummy";
      schema.owner = test.owner;
      schema.access = Access.PUBLIC;
      schema = addOrUpdateSchema(schema);

      long now = System.currentTimeMillis();
      String ts = String.valueOf(now);
      JsonNode data = JsonNodeFactory.instance.objectNode()
              .put("$schema", schema.uri)
              .put("value", "foobar");
      uploadRun(ts, ts, test.name, test.owner, Access.PUBLIC, null, schema.uri, null, data);

      int datasetId = -1;
      while (System.currentTimeMillis() < now + 10000) {
         DatasetService.DatasetList datasets = jsonRequest().get("/api/dataset/list/" + test.id).then().statusCode(200).extract().body().as(DatasetService.DatasetList.class);
         if (datasets.datasets.isEmpty()) {
            //noinspection BusyWait
            Thread.sleep(50);
         } else {
            Assertions.assertEquals(1, datasets.datasets.size());
            datasetId = datasets.datasets.iterator().next().id;
         }
      }
      Assertions.assertNotEquals(-1, datasetId);

      Label label = new Label();
      label.name = "foo";
      label.schemaId = schema.id;
      label.function = "value => value";
      label.extractors = Collections.singletonList(new Extractor("value", "$.value", false));
      DatasetService.LabelPreview preview = jsonRequest().body(label).post("/api/dataset/"+datasetId+"/previewLabel").then().statusCode(200).extract().body().as(DatasetService.LabelPreview.class);
      Assertions.assertEquals("foobar", preview.value.textValue());
   }

   @org.junit.jupiter.api.Test
   public void runExperiment() throws InterruptedException {
      Test test = createExampleTest("supersecret");
      test = createTest(test);

      try {
         //1. Create new Schema
         Schema schema = new Schema();
         schema.uri = "urn:test-schema:0.1";
         schema.name = "test";
         schema.owner = test.owner;
         schema.access = Access.PUBLIC;
         schema = addOrUpdateSchema(schema);

         //2. Define schema labels
         Label lblCpu = new Label();
         lblCpu.name = "cpu";
         Extractor cpuExtractor = new Extractor("cpu", "$.data.cpu", false);
         lblCpu.extractors = List.of(cpuExtractor);
         lblCpu.access = Access.PUBLIC;
         lblCpu.owner = test.owner;
         lblCpu.metrics = true;
         lblCpu.filtering = false;
         lblCpu.id = addOrUpdateLabel(schema.id, lblCpu);

         Label lblThroughput = new Label();
         lblThroughput.name = "throughput";
         Extractor throughputExtractor = new Extractor("throughput", "$.data.throughput", false);
         lblThroughput.extractors = List.of(throughputExtractor);
         lblThroughput.access = Access.PUBLIC;
         lblThroughput.owner = test.owner;
         lblThroughput.metrics = true;
         lblThroughput.filtering = false;
         lblThroughput.id = addOrUpdateLabel(schema.id, lblThroughput);

         Label lblJob = new Label();
         lblJob.name = "job";
         Extractor jobExtractor = new Extractor("job", "$.job", false);
         lblJob.extractors = List.of(jobExtractor);
         lblJob.access = Access.PUBLIC;
         lblJob.owner = test.owner;
         lblJob.metrics = false;
         lblJob.filtering = true;
         lblJob.id = addOrUpdateLabel(schema.id, lblJob);

         Label lblBuildID = new Label();
         lblBuildID.name = "build-id";
         Extractor buildIDExtractor = new Extractor("build-id", "$.\"build-id\"", false);
         lblBuildID.extractors = List.of(buildIDExtractor);
         lblBuildID.access = Access.PUBLIC;
         lblBuildID.owner = test.owner;
         lblBuildID.metrics = false;
         lblBuildID.filtering = true;
         lblBuildID.id = addOrUpdateLabel(schema.id, lblBuildID);

         //3. Config change detection variables
         Variable variable = new Variable();
         variable.testId = test.id;
         variable.name = "throughput";
         variable.order = 0;
         variable.labels = mapper.readTree("[ \"throughput\" ]");
         ChangeDetection changeDetection = new ChangeDetection();
         changeDetection.model = "relativeDifference";

         changeDetection.config = mapper.readTree("{" +
                 "          \"window\": 1," +
                 "          \"filter\": \"mean\"," +
                 "          \"threshold\": 0.2," +
                 "          \"minPrevious\": 5" +
                 "        }");
         variable.changeDetection = new HashSet<>();
         variable.changeDetection.add(changeDetection);

         updateVariables( test.id, Collections.singletonList(variable));

         //need this for defining experiment
         List<Variable> variableList = variables(test.id);

         AlertingService.ChangeDetectionUpdate update = new AlertingService.ChangeDetectionUpdate();
         update.fingerprintLabels = Collections.emptyList();
         update.timelineLabels = Collections.emptyList();
         updateChangeDetection(test.id, update);


         //4. Define experiments
         ExperimentProfile experimentProfile = new ExperimentProfile();
         experimentProfile.id = -1;  //TODO: fix profile add/Update
         experimentProfile.name = "robust-experiment";
         experimentProfile.selectorLabels = mapper.readTree(" [ \"job\" ] ");
         experimentProfile.selectorFilter = "value => !!value";
         experimentProfile.baselineLabels = mapper.readTree(" [ \"build-id\" ] ");
         experimentProfile.baselineFilter = "value => value == 1";

         ExperimentComparison experimentComparison = new ExperimentComparison();
         experimentComparison.model = "relativeDifference";
         experimentComparison.variableId = variableList.get(0).id; //should only contain one variable
         experimentComparison.config = mapper.readValue("{" +
                 "          \"maxBaselineDatasets\": 0," +
                 "          \"threshold\": 0.1," +
                 "          \"greaterBetter\": true" +
                 "        }", ObjectNode.class);


         experimentProfile.comparisons = Collections.singletonList(experimentComparison);

         addOrUpdateProfile(test.id, experimentProfile);

         //5. upload some data
         Test finalTest = test;
         Schema finalSchema = schema;
         Consumer<JsonNode> uploadData = (payload) -> uploadRun("$.start", "$.stop", finalTest.name, finalTest.owner, Access.PUBLIC, null, finalSchema.uri, null, payload);

         uploadData.accept(mapper.readTree(resourceToString("data/experiment-ds1.json")));
         uploadData.accept(mapper.readTree(resourceToString("data/experiment-ds2.json")));
         uploadData.accept(mapper.readTree(resourceToString("data/experiment-ds3.json")));

         //6. run experiments
         RunService.RunsSummary runsSummary = listTestRuns(test.id, false, null, null, "name", SortDirection.Ascending);

         Integer lastRunID = runsSummary.runs.stream().map(run -> run.id).max((Comparator.comparingInt(anInt -> anInt))).get();

         //wait for dataset(s) to be calculated
         waitForDatasets(lastRunID);

         RunService.RunExtended extendedRun = getRun(lastRunID, null);

         assertNotNull(extendedRun.datasets);

         Integer maxDataset = Arrays.stream(extendedRun.datasets).max(Comparator.comparingInt(anInt -> anInt)).get();

         List<ExperimentService.ExperimentResult> experimentResults = runExperiments(maxDataset);

         assertNotNull(experimentResults);
         assertTrue(experimentResults.size() > 0);

      }
      catch (Exception e) {
         e.printStackTrace();
         fail(e.getMessage());
      }
   }

   private JsonNode getBySchema(JsonNode data, String schema) {
      JsonNode foo = StreamSupport.stream(data.spliterator(), false)
            .filter(item -> schema.equals(item.path("$schema").asText())).findFirst().orElse(null);
      assertNotNull(foo);
      return foo;
   }

   private ObjectNode simpleObject(String schema, String key, String value) {
      ObjectNode data = JsonNodeFactory.instance.objectNode();
      data.put("$schema", schema);
      data.put(key, value);
      return data;
   }

   private JsonNode getData(int runId, Schema schema) {
      RequestSpecification request = jsonRequest();
      if (schema != null) {
         request = request.queryParam("schemaUri", schema.uri);
      }
      return request.get("/api/run/" + runId + "/data").then().extract().body().as(JsonNode.class);
   }
}
