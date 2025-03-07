package io.hyperfoil.tools.horreum.svc;

import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.horreum.api.data.Dataset;
import io.hyperfoil.tools.horreum.api.data.JsonpathValidation;
import io.hyperfoil.tools.horreum.bus.MessageBusChannels;
import io.hyperfoil.tools.horreum.entity.alerting.DataPointDAO;
import io.hyperfoil.tools.horreum.hibernate.JsonBinaryType;
import io.hyperfoil.tools.horreum.mapper.DatasetMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.TransactionRequiredException;
import jakarta.persistence.Tuple;
import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.hyperfoil.tools.horreum.api.SortDirection;
import io.hyperfoil.tools.horreum.api.data.Access;
import io.hyperfoil.tools.horreum.api.data.Run;
import io.hyperfoil.tools.horreum.api.data.ValidationError;
import io.hyperfoil.tools.horreum.entity.data.*;
import io.hyperfoil.tools.horreum.mapper.RunMapper;
import io.hyperfoil.tools.horreum.api.services.RunService;
import io.hyperfoil.tools.horreum.api.services.SchemaService;
import io.hyperfoil.tools.horreum.bus.MessageBus;
import io.hyperfoil.tools.horreum.entity.PersistentLogDAO;
import io.hyperfoil.tools.horreum.entity.alerting.TransformationLogDAO;
import io.hyperfoil.tools.horreum.server.WithRoles;
import io.hyperfoil.tools.horreum.server.WithToken;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.runtime.Startup;
import io.quarkus.security.identity.SecurityIdentity;

import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import static com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
import static io.hyperfoil.tools.horreum.entity.data.SchemaDAO.QUERY_1ST_LEVEL_BY_RUNID_TRANSFORMERID_SCHEMA_ID;
import static io.hyperfoil.tools.horreum.entity.data.SchemaDAO.QUERY_2ND_LEVEL_BY_RUNID_TRANSFORMERID_SCHEMA_ID;
import static io.hyperfoil.tools.horreum.entity.data.SchemaDAO.QUERY_TRANSFORMER_TARGETS;

@ApplicationScoped
@Startup
public class RunServiceImpl implements RunService {
   private static final Logger log = Logger.getLogger(RunServiceImpl.class);
   //@formatter:off
   private static final String FIND_AUTOCOMPLETE =
         "SELECT * FROM (" +
            "SELECT DISTINCT jsonb_object_keys(q) AS key " +
            "FROM run, jsonb_path_query(run.data, ? ::::jsonpath) q " +
            "WHERE jsonb_typeof(q) = 'object') AS keys " +
         "WHERE keys.key LIKE CONCAT(?, '%');";
   protected static final String FIND_RUNS_WITH_URI = "SELECT id, testid FROM run WHERE NOT trashed AND (data->>'$schema' = ?1 OR (" +
         "CASE WHEN jsonb_typeof(data) = 'object' THEN ?1 IN (SELECT values.value->>'$schema' FROM jsonb_each(data) as values) " +
         "WHEN jsonb_typeof(data) = 'array' THEN ?1 IN (SELECT jsonb_array_elements(data)->>'$schema') ELSE false END) OR " +
         "(metadata IS NOT NULL AND ?1 IN (SELECT jsonb_array_elements(metadata)->>'$schema')))";
   //@formatter:on
   private static final String[] CONDITION_SELECT_TERMINAL = { "==", "!=", "<>", "<", "<=", ">", ">=", " " };
   private static final String UPDATE_TOKEN = "UPDATE run SET token = ? WHERE id = ?";
   private static final String CHANGE_ACCESS = "UPDATE run SET owner = ?, access = ? WHERE id = ?";
   private static final String SCHEMA_USAGE = "COALESCE(jsonb_agg(jsonb_build_object(" +
         "'id', schema.id, 'uri', rs.uri, 'name', schema.name, 'source', rs.source, " +
         "'type', rs.type, 'key', rs.key, 'hasJsonSchema', schema.schema IS NOT NULL)), '[]')";

   @Inject
   EntityManager em;

   @Inject
   SecurityIdentity identity;

   @Inject
   TransactionManager tm;

   @Inject
   SqlServiceImpl sqlService;

   @Inject
   MessageBus messageBus;

   @Inject
   TestServiceImpl testService;

   @Inject
   ObjectMapper mapper;

   @Inject
   ServiceMediator mediator;

   @Inject
   Session session;

   @Transactional
   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   void onTestDeleted(int testId) {
      log.debugf("Trashing runs for test (%d)", testId);
      ScrollableResults<Integer> results = session.createNativeQuery("SELECT id FROM run WHERE testid = ?1", Integer.class)
              .setParameter(1, testId)
              .setReadOnly(true)
              .setFetchSize(100)
              .scroll(ScrollMode.FORWARD_ONLY);
      while (results.next()) {
         int id = results.get();
         trashDueToTestDeleted(id);
      }
   }

   // plain trash does not have the right privileges and @RolesAllowed would cause ContextNotActiveException
   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   void trashDueToTestDeleted(int id) {
      trashInternal(id, true);
   }

   // We cannot run this without a transaction (to avoid timeout) because we have not request going on
   // and EM has to bind its lifecycle either to current request or transaction.
   @Transactional(Transactional.TxType.REQUIRES_NEW)
   @TransactionConfiguration(timeout = 3600) // 1 hour, this may run a long time
   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   void onNewOrUpdatedSchema(int schemaId) {
      SchemaDAO schema = SchemaDAO.findById(schemaId);
      if (schema == null) {
         log.errorf("Cannot process schema add/update: cannot load schema %d", schemaId);
         return;
      }
      processNewOrUpdatedSchema(schema);
   }

   @Transactional
   void processNewOrUpdatedSchema(SchemaDAO schema) {
      // we don't have to care about races with new runs
      findRunsWithUri(schema.uri, (runId, testId) -> {
         log.debugf("Recalculate Datasets for run %d - schema %d (%s) changed", runId, schema.id, schema.uri);
         onNewOrUpdatedSchemaForRun(runId, schema.id);
      });
   }

   void findRunsWithUri(String uri, BiConsumer<Integer, Integer> consumer) {
      ScrollableResults<RunFromUri> results =
             session.createNativeQuery(FIND_RUNS_WITH_URI, Tuple.class).setParameter(1, uri)
                     .setTupleTransformer((tuple, aliases) -> {
                        RunFromUri r = new RunFromUri();
                        r.id = (int) tuple[0];
                        r.testId = (int) tuple[1];
                        return r;
                     })
                     .setFetchSize(100)
                     .scroll(ScrollMode.FORWARD_ONLY);
      while (results.next()) {
         RunFromUri r = results.get();
         consumer.accept( r.id, r.testId);
      }
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   void onNewOrUpdatedSchemaForRun(int runId, int schemaId) {
      em.createNativeQuery("SELECT update_run_schemas(?1)::::text").setParameter(1, runId).getSingleResult();
      //clear validation error tables by schemaId
      em.createNativeQuery("DELETE FROM dataset_validationerrors WHERE schema_id = ?1")
              .setParameter(1, schemaId).executeUpdate();
      em.createNativeQuery("DELETE FROM run_validationerrors WHERE schema_id = ?1")
              .setParameter(1, schemaId).executeUpdate();

      Util.registerTxSynchronization(tm, txStatus -> mediator.queueRunRecalculation(runId));
//      transform(runId, true);
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public RunExtended getRun(int id, String token) {

      RunExtended runExtended = null;

      String extendedData = (String) Util.runQuery(em, "SELECT (to_jsonb(run) || jsonb_build_object(" +
              "'schemas', (SELECT " + SCHEMA_USAGE + " FROM run_schemas rs JOIN schema ON rs.schemaid = schema.id WHERE runid = run.id), " +
              "'testname', (SELECT name FROM test WHERE test.id = run.testid), " +
              "'datasets', (SELECT jsonb_agg(id ORDER BY id) FROM dataset WHERE runid = run.id), " +
              "'validationErrors', (SELECT jsonb_agg(jsonb_build_object('schemaId', schema_id, 'error', error)) FROM run_validationerrors WHERE run_id = ?1)" +
              "))::::text FROM run WHERE id = ?1", id);
      try {
         runExtended = mapper.readValue(extendedData, RunExtended.class);
      } catch (JsonProcessingException e) {
         throw ServiceException.serverError("Could not retrieve extended run");
      }

      return runExtended;
   }

   @WithRoles
   @Override
   public RunSummary getRunSummary(int id, String token) {
      Query query = em.createNativeQuery("SELECT run.id, run.start, run.stop, run.testid, " +
            "run.owner, run.access, run.token, run.trashed, run.description, run.metadata IS NOT NULL as has_metadata, " +
            "(SELECT name FROM test WHERE test.id = run.testid) as testname, " +
            "(SELECT " + SCHEMA_USAGE + " FROM run_schemas rs JOIN schema ON schema.id = rs.schemaid WHERE rs.runid = run.id) as schemas, " +
            "(SELECT json_agg(id ORDER BY id) FROM dataset WHERE runid = run.id) as datasets, " +
            "(SELECT jsonb_agg(jsonb_build_object('schemaId', schema_id, 'error', error)) AS errors FROM run_validationerrors WHERE run_id = ?1 GROUP BY run_id) AS validationErrors " +
            "FROM run where id = ?1").setParameter(1, id);
      initTypes(query);
      return createSummary((Object[]) query.getSingleResult());
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public Object getData(int id, String token, String schemaUri) {
      if (schemaUri == null || schemaUri.isEmpty()) {
         return Util.runQuery(em, "SELECT data#>>'{}' from run where id = ?", id);
      } else {
         String sqlQuery = "SELECT (CASE " +
               "WHEN rs.type = 0 THEN run.data " +
               "WHEN rs.type = 1 THEN run.data->rs.key " +
               "ELSE run.data->(rs.key::::integer) " +
               "END)#>>'{}' FROM run JOIN run_schemas rs ON rs.runid = run.id WHERE id = ?1 AND rs.source = 0 AND rs.uri = ?2";
         return Util.runQuery(em, sqlQuery, id, schemaUri);
      }
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public JsonNode getMetadata(int id, String token, String schemaUri) {
      String result;
      if (schemaUri == null || schemaUri.isEmpty()) {
         result = (String) Util.runQuery(em,  "SELECT metadata#>>'{}' from run where id = ?", id);
      } else {
         String sqlQuery = "SELECT run.metadata->(rs.key::::integer)#>>'{}' FROM run " +
               "JOIN run_schemas rs ON rs.runid = run.id WHERE id = ?1 AND rs.source = 1 AND rs.uri = ?2";
         result = (String) Util.runQuery(em, sqlQuery, id, schemaUri);
      }
      try {
         return Util.OBJECT_MAPPER.readTree(result);
      } catch (JsonProcessingException e) {
         throw ServiceException.serverError(e.getMessage());
      }
   }



   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public String resetToken(int id) {
      return updateToken(id, Tokens.generateToken());
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public String dropToken(int id) {
      return updateToken(id, null);
   }

   private String updateToken(int id, String token) {
      Query query = em.createNativeQuery(UPDATE_TOKEN);
      query.setParameter(1, token);
      query.setParameter(2, id);
      if (query.executeUpdate() != 1) {
         throw ServiceException.serverError("Token reset failed (missing permissions?)");
      } else {
         return token;
      }
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   // TODO: it would be nicer to use @FormParams but fetchival on client side doesn't support that
   public void updateAccess(int id, String owner, Access access) {
      Query query = em.createNativeQuery(CHANGE_ACCESS);
      query.setParameter(1, owner);
      query.setParameter(2, access.ordinal());
      query.setParameter(3, id);
      if (query.executeUpdate() != 1) {
         throw ServiceException.serverError("Access change failed (missing permissions?)");
      }
   }

   @PermitAll // all because of possible token-based upload
   @WithRoles
   @WithToken
   @Transactional
   @Override
   public Response add(String testNameOrId, String owner, Access access, String token, Run run) {
      if (owner != null) {
         run.owner = owner;
      }
      if (access != null) {
         run.access = access;
      }
      log.debugf("About to add new run to test %s using owner", testNameOrId, owner);
      if(testNameOrId == null || testNameOrId.isEmpty()) {
         if (run.testid == null || run.testid == 0) {
            return Response.status(Response.Status.BAD_REQUEST).entity("No test name or id provided").build();
         }
         else
            testNameOrId = run.testid.toString();
      }

      TestDAO test = testService.ensureTestExists(testNameOrId, token);
      run.testid = test.id;
      Integer runId = addAuthenticated(RunMapper.to(run), test);
      return Response.status(Response.Status.OK).entity(String.valueOf(runId)).header(HttpHeaders.LOCATION, "/run/" + runId).build();
   }


   @Override
   public Response addRunFromData(String start, String stop, String test,
                                  String owner, Access access, String token,
                                  String schemaUri, String description,
                                  String data) {
      return addRunFromData(start, stop, test, owner, access, token, schemaUri, description, data, null);
   }

   @Override
   public Response addRunFromData(String start, String stop, String test, String owner, Access access, String token, String schemaUri, String description, FileUpload data, FileUpload metadata) {
      if (data == null) {
         log.debugf("Failed to upload for test %s with description %s because of missing data.", test, description);
         throw ServiceException.badRequest("No data!");
      } else if (!MediaType.APPLICATION_JSON.equals(data.contentType())) {
         log.debugf("Failed to upload for test %s with description %s because of wrong data content type: %s.", test, description, data.contentType());
         throw ServiceException.badRequest("Part 'data' must use content-type: application/json, currently: " + data.contentType());
      }
      if (metadata != null && !MediaType.APPLICATION_JSON.equals(metadata.contentType())) {
         log.debugf("Failed to upload for test %s with description %s because of wrong metadata content type: %s.", test, description, metadata.contentType());
         throw ServiceException.badRequest("Part 'metadata' must use content-type: application/json, currently: " + metadata.contentType());
      }
      JsonNode dataNode;
      JsonNode metadataNode = null;
      try {
         dataNode = Util.OBJECT_MAPPER.readTree(data.uploadedFile().toFile());
         if (metadata != null) {
            metadataNode = Util.OBJECT_MAPPER.readTree(metadata.uploadedFile().toFile());
            if (metadataNode.isArray()) {
               for (JsonNode item : metadataNode) {
                  if (!item.isObject()) {
                     log.debugf("Failed to upload for test %s with description %s because of wrong item in metadata: %s.", test, description, item);
                     throw ServiceException.badRequest("One of metadata elements is not an object!");
                  } else if (!item.has("$schema")) {
                     log.debugf("Failed to upload for test %s with description %s because of missing schema in metadata: %s.", test, description, item);
                     throw ServiceException.badRequest("One of metadata elements is missing a schema!");
                  }
               }
            } else if (metadataNode.isObject()) {
               if (!metadataNode.has("$schema")) {
                  log.debugf("Failed to upload for test %s with description %s because of missing schema in metadata.", test, description);
                  throw ServiceException.badRequest("Metadata is missing schema!");
               }
               metadataNode = instance.arrayNode().add(metadataNode);
            }
         }
      } catch (IOException e) {
         log.error("Failed to read data/metadata from upload file", e);
         throw ServiceException.badRequest("Provided data/metadata can't be read (JSON encoding problem?)");
      }
      return addRunFromData(start, stop, test, owner, access, token, schemaUri, description, dataNode.toString(), metadataNode);
   }

   @Override
   public void waitForDatasets(int runId) {
      //first check if we have a dataset already generated
      if(DatasetDAO.count("run.id = ?1", runId) > 0)
         return;

      // wait for at least one (1) dataset. we do not know how many datasets will be produced
      CountDownLatch dsAvailableLatch = new CountDownLatch(1);
      // create new dataset listener
      messageBus.subscribe(MessageBusChannels.DATASET_NEW,"DatasetService", Dataset.EventNew.class, (event) -> {
         if (event.runId == runId) {
            dsAvailableLatch.countDown();
         }
      });

      try {
         // if there is not already a dataset in the db, wait for msg back from db that at least one dataset is available
         if (DatasetDAO.find("run.id", runId).count() == 0) {
            dsAvailableLatch.await(10L, TimeUnit.SECONDS);
         }
      } catch (InterruptedException e) {
         //TODO :: make timeout configurable
         ServiceException.serverError("Dataset was not produced within 10 seconds");
      }
   }

   @PermitAll // all because of possible token-based upload
   @Transactional
   @WithRoles
   @WithToken
   Response addRunFromData(String start, String stop, String test,
                                String owner, Access access, String token,
                                String schemaUri, String description,
                                String stringData, JsonNode metadata) {
      if (stringData == null) {
         log.debugf("Failed to upload for test %s with description %s because of missing data.", test, description);
         throw ServiceException.badRequest("No data!");
      }
      JsonNode data = null;
      try {
         data = Util.OBJECT_MAPPER.readValue(stringData, JsonNode.class);
      } catch (JsonProcessingException e) {
         throw ServiceException.badRequest("Could not map incoming data to JsonNode: "+e.getMessage());
      }
      Object foundTest = findIfNotSet(test, data);
      Object foundStart = findIfNotSet(start, data);
      Object foundStop = findIfNotSet(stop, data);
      Object foundDescription = findIfNotSet(description, data);

      if (schemaUri != null && !schemaUri.isEmpty()) {
         if (data.isObject()) {
            ((ObjectNode) data).put("$schema", schemaUri);
         } else if (data.isArray()) {
            data.forEach(node -> {
               if (node.isObject() && !node.hasNonNull("$schema")) {
                  ((ObjectNode) node).put("$schema", schemaUri);
               }
            });
         }
      }

      String testNameOrId = foundTest == null ? null : foundTest.toString().trim();
      if (testNameOrId == null || testNameOrId.isEmpty()) {
         log.debugf("Failed to upload for test %s with description %s as the test cannot be identified.", test, description);
         throw ServiceException.badRequest("Cannot identify test name.");
      }

      Instant startInstant = toInstant(foundStart);
      Instant stopInstant = toInstant(foundStop);
      if (startInstant == null) {
         log.debugf("Failed to upload for test %s with description %s; cannot parse start time %s (%s)", test, description, foundStart, start);
         throw ServiceException.badRequest("Cannot parse start time from " + foundStart + " (" + start + ")");
      } else if (stopInstant == null) {
         log.debugf("Failed to upload for test %s with description %s; cannot parse start time %s (%s)", test, description, foundStop,stop);
         throw ServiceException.badRequest("Cannot parse stop time from " + foundStop + " (" + stop + ")");
      }

      TestDAO testEntity = testService.ensureTestExists(testNameOrId, token);

      log.debugf("Creating new run for test %s(%d) with description %s", testEntity.name, testEntity.id, foundDescription);

      RunDAO run = new RunDAO();
      run.testid = testEntity.id;
      run.start = startInstant;
      run.stop = stopInstant;
      run.description = foundDescription != null ? foundDescription.toString() : null;
      run.data = data;
      run.metadata = metadata;
      run.owner = owner;
      run.access = access;
      // Some triggered functions in the database need to be able to read the just-inserted run
      // otherwise RLS policies will fail. That's why we reuse the token for the test and later wipe it out.
      run.token = token;

      Integer runId = addAuthenticated(run, testEntity);
      if (token != null) {
         // TODO: remove the token
      }
      return Response.status(Response.Status.OK).entity(String.valueOf(runId)).header(HttpHeaders.LOCATION, "/run/" + runId).build();
   }

   private Object findIfNotSet(String value, JsonNode data) {
      if (value != null && !value.isEmpty()) {
         if (value.startsWith("$.")) {
            return Util.findJsonPath(data, value);
         } else {
            return value;
         }
      } else {
         return null;
      }
   }

   private Instant toInstant(Object time) {
      if (time == null) {
         return null;
      } else if (time instanceof Number) {
         return Instant.ofEpochMilli(((Number) time).longValue());
      } else {
         try {
            return Instant.ofEpochMilli(Long.parseLong((String) time));
         } catch (NumberFormatException e) {
            // noop
         }
         try {
            return ZonedDateTime.parse(time.toString().trim(), DateTimeFormatter.ISO_DATE_TIME).toInstant();
         } catch (DateTimeParseException e) {
            return null;
         }
      }
   }

   private Integer addAuthenticated(RunDAO run, TestDAO test) {
      // Id will be always generated anew
      run.id = null;
      //if run.metadata is null on the client, it will be converted to a NullNode, not null...
      if(run.metadata != null && run.metadata.isNull())
         run.metadata = null;

      if (run.owner == null) {
         List<String> uploaders = identity.getRoles().stream().filter(role -> role.endsWith("-uploader")).collect(Collectors.toList());
         if (uploaders.size() != 1) {
            log.debugf("Failed to upload for test %s: no owner, available uploaders: %s", test.name, uploaders);
            throw ServiceException.badRequest("Missing owner and cannot select single default owners; this user has these uploader roles: " + uploaders);
         }
         String uploader = uploaders.get(0);
         run.owner = uploader.substring(0, uploader.length() - 9) + "-team";
      } else if (!Objects.equals(test.owner, run.owner) && !identity.getRoles().contains(run.owner)) {
         log.debugf("Failed to upload for test %s: requested owner %s, available roles: %s", test.name, run.owner, identity.getRoles());
         throw ServiceException.badRequest("This user does not have permissions to upload run for owner=" + run.owner);
      }
      if (run.access == null) {
         run.access = Access.PRIVATE;
      }
      log.debugf("Uploading with owner=%s and access=%s", run.owner, run.access);

      try {
         if (run.id == null) {
            em.persist(run);
         } else {
            trashConnectedDatasets(run.id, run.testid);
            em.merge(run);
         }
         em.flush();
      } catch (Exception e) {
         log.error("Failed to persist run.", e);
         throw ServiceException.serverError("Failed to persist run");
      }
      log.debugf("Upload flushed, run ID %d", run.id);

      mediator.newRun(RunMapper.from(run));
      transform(run.id, false);
      if(mediator.testMode())
         Util.registerTxSynchronization(tm, txStatus -> messageBus.publish(MessageBusChannels.RUN_NEW, test.id, RunMapper.from(run)));

      return run.id;
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public List<String> autocomplete(String query) {
      if (query == null || query.isEmpty()) {
         return null;
      }
      String jsonpath = query.trim();
      String incomplete = "";
      if (jsonpath.endsWith(".")) {
         jsonpath = jsonpath.substring(0, jsonpath.length() - 1);
      } else {
         int lastDot = jsonpath.lastIndexOf('.');
         if (lastDot > 0) {
            incomplete = jsonpath.substring(lastDot + 1);
            jsonpath = jsonpath.substring(0, lastDot);
         } else {
            incomplete = jsonpath;
            jsonpath = "$.**";
         }
      }
      int conditionIndex = jsonpath.indexOf('@');
      if (conditionIndex >= 0) {
         int conditionSelectEnd = jsonpath.length();
         for (String terminal : CONDITION_SELECT_TERMINAL) {
            int ti = jsonpath.indexOf(terminal, conditionIndex + 1);
            if (ti >= 0) {
               conditionSelectEnd = Math.min(conditionSelectEnd, ti);
            }
         }
         String conditionSelect = jsonpath.substring(conditionIndex + 1, conditionSelectEnd);
         int queryIndex = jsonpath.indexOf('?');
         if (queryIndex < 0) {
            // This is a shortcut query '@.foo...'
            jsonpath = "$.**" + conditionSelect;
         } else if (queryIndex > conditionIndex) {
            // Too complex query with multiple conditions
            return Collections.emptyList();
         } else {
            while (queryIndex > 0 && Character.isWhitespace(jsonpath.charAt(queryIndex - 1))) {
               --queryIndex;
            }
            jsonpath = jsonpath.substring(0, queryIndex) + conditionSelect;
         }
      }
      if (!jsonpath.startsWith("$")) {
         jsonpath = "$.**." + jsonpath;
      }
      try {
         NativeQuery<String> findAutocomplete = session.createNativeQuery(FIND_AUTOCOMPLETE, String.class);
         findAutocomplete.setParameter(1, jsonpath);
         findAutocomplete.setParameter(2, incomplete);
         List<String> results = findAutocomplete.getResultList();
         return results.stream().map(option ->
               option.matches("^[a-zA-Z0-9_-]*$") ? option : "\"" + option + "\"")
               .collect(Collectors.toList());
      } catch (PersistenceException e) {
         throw ServiceException.badRequest("Failed processing query '" + query + "':\n" + e.getLocalizedMessage());
      }
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public RunsSummary listAllRuns(String query, boolean matchAll, String roles, boolean trashed,
                                  Integer limit, Integer page, String sort, SortDirection direction) {
      StringBuilder sql = new StringBuilder("SELECT run.id, run.start, run.stop, run.testId, ")
         .append("run.owner, run.access, run.token, run.trashed, run.description, ")
         .append("run.metadata IS NOT NULL AS has_metadata, test.name AS testname, ")
         .append("'[]'::::jsonb AS schemas, '[]'::::jsonb AS datasets, '[]'::::jsonb AS validationErrors ")
         .append("FROM run JOIN test ON test.id = run.testId WHERE ");
      String[] queryParts;
      boolean whereStarted = false;
      if (query == null || query.isEmpty()) {
         queryParts = new String[0];
      } else {
         query = query.trim();
         if (query.startsWith("$") || query.startsWith("@")) {
            queryParts = new String[] { query };
         } else {
            queryParts = query.split("([ \t\n,]+)|\\bOR\\b");
         }
         sql.append("(");
         for (int i = 0; i < queryParts.length; ++i) {
            if (i != 0) {
               sql.append(matchAll ? " AND " : " OR ");
            }
            sql.append("jsonb_path_exists(data, ?").append(i + 1).append(" ::::jsonpath)");
            if (queryParts[i].startsWith("$")) {
               // no change
            } else if (queryParts[i].startsWith("@")) {
               queryParts[i] = "$.** ? (" + queryParts[i] + ")";
            } else {
               queryParts[i] = "$.**." + queryParts[i];
            }
         }
         sql.append(")");
         whereStarted = true;
      }

      whereStarted = Roles.addRolesSql(identity, "run", sql, roles, queryParts.length + 1, whereStarted ? " AND" : null) || whereStarted;
      if (!trashed) {
         if (whereStarted) {
            sql.append(" AND ");
         }
         sql.append(" trashed = false ");
      }
      Util.addPaging(sql, limit, page, sort, direction);

      NativeQuery<Object[]> sqlQuery = session.createNativeQuery(sql.toString(), Object[].class);
      for (int i = 0; i < queryParts.length; ++i) {
         sqlQuery.setParameter(i + 1, queryParts[i]);
      }

      Roles.addRolesParam(identity, sqlQuery, queryParts.length + 1, roles);

      try {
         List<Object[]> runs = sqlQuery.getResultList();

         RunsSummary summary = new RunsSummary();
         // TODO: total does not consider the query but evaluating all the expressions would be expensive
         summary.total = trashed ? RunDAO.count() : RunDAO.count("trashed = false");
         summary.runs = runs.stream().map(this::createSummary).collect(Collectors.toList());
         return summary;
      } catch (PersistenceException pe) {
         // In case of an error PostgreSQL won't let us execute another query in the same transaction
         try {
            Transaction old = tm.suspend();
            try {
               for (String jsonpath : queryParts) {
                  JsonpathValidation result = sqlService.testJsonPathInternal(jsonpath);
                  if (!result.valid) {
                     throw new WebApplicationException(Response.status(400).entity(result).build());
                  }
               }
            } finally {
               tm.resume(old);
            }
         } catch (InvalidTransactionException | SystemException e) {
            // ignore
         }
         throw new WebApplicationException(pe, 500);
      }
   }

   private void initTypes(Query query) {
      query.unwrap(NativeQuery.class)
            .addScalar("id", StandardBasicTypes.INTEGER)
            .addScalar("start", StandardBasicTypes.INSTANT)
            .addScalar("stop", StandardBasicTypes.INSTANT)
            .addScalar("testid", StandardBasicTypes.INTEGER)
            .addScalar("owner", StandardBasicTypes.TEXT)
            .addScalar("access", StandardBasicTypes.INTEGER)
            .addScalar("token", StandardBasicTypes.TEXT)
            .addScalar("trashed", StandardBasicTypes.BOOLEAN)
            .addScalar("description", StandardBasicTypes.TEXT)
            .addScalar("has_metadata", StandardBasicTypes.BOOLEAN)
            .addScalar("testname", StandardBasicTypes.TEXT)
            .addScalar("schemas", JsonBinaryType.INSTANCE)
            .addScalar("datasets", JsonBinaryType.INSTANCE)
            .addScalar("validationErrors", JsonBinaryType.INSTANCE);
   }

   private RunSummary createSummary(Object[] row) {

      RunSummary run = new RunSummary();
      run.id = (int) row[0];
      if(row[1] != null)
         run.start = ((Instant) row[1]);
      if(row[2] != null)
         run.stop = ((Instant) row[2]);
      run.testid = (int) row[3];
      run.owner = (String) row[4];
      run.access = Access.fromInt((int) row[5]);
      run.token = (String) row[6];
      run.trashed = (boolean) row[7];
      run.description = (String) row[8];
      run.hasMetadata = (boolean) row[9];
      run.testname = (String) row[10];

      //if we send over an empty JsonNode object it will be a NullNode, that can be cast to a string
      if(row[11] != null && !(row[11] instanceof String)) {
         run.schemas = Util.OBJECT_MAPPER.convertValue(row[11], new TypeReference<List<SchemaService.SchemaUsage>>() {
         });
      }
      //if we send over an empty JsonNode object it will be a NullNode, that can be cast to a string
      if(row[12] != null && !(row[12] instanceof String)) {
         try {
            run.datasets = Util.OBJECT_MAPPER.treeToValue(((ArrayNode) row[12]), Integer[].class);
         } catch (JsonProcessingException e) {
            log.warnf("Could not map datasets to array");
         }
      }
      //if we send over an empty JsonNode object it will be a NullNode, that can be cast to a string
      if(row[13] != null && !(row[13] instanceof String)) {
         try {
            run.validationErrors = Util.OBJECT_MAPPER.treeToValue(((ArrayNode) row[13]), ValidationError[].class);
         } catch (JsonProcessingException e) {
            log.warnf("Could not map validation errors to array");
         }
      }
      return run;
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public RunCount runCount(int testId) {
      RunCount counts = new RunCount();
      counts.total = RunDAO.count("testid = ?1", testId);
      counts.active = RunDAO.count("testid = ?1 AND trashed = false", testId);
      counts.trashed = counts.total - counts.active;
      return counts;
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public RunsSummary listTestRuns(int testId, boolean trashed,
                                   Integer limit, Integer page, String sort, SortDirection direction) {
      StringBuilder sql = new StringBuilder("WITH schema_agg AS (")
            .append("    SELECT " + SCHEMA_USAGE + " AS schemas, rs.runid ")
            .append("        FROM run_schemas rs JOIN schema ON schema.id = rs.schemaid WHERE rs.testid = ?1 GROUP BY rs.runid")
            .append("), dataset_agg AS (")
            .append("    SELECT runid, jsonb_agg(id ORDER BY id) as datasets FROM dataset WHERE testid = ?1 GROUP BY runid")
            .append("), validation AS (")
            .append("    SELECT run_id, jsonb_agg(jsonb_build_object('schemaId', schema_id, 'error', error)) AS errors FROM run_validationerrors GROUP BY run_id")
            .append(") SELECT run.id, run.start, run.stop, run.testid, run.owner, run.access, run.token, run.trashed, run.description, ")
            .append("run.metadata IS NOT NULL AS has_metadata, test.name AS testname, ")
            .append("schema_agg.schemas AS schemas, ")
            .append("COALESCE(dataset_agg.datasets, '[]') AS datasets, ")
            .append("COALESCE(validation.errors, '[]') AS validationErrors FROM run ")
            .append("LEFT JOIN schema_agg ON schema_agg.runid = run.id ")
            .append("LEFT JOIN dataset_agg ON dataset_agg.runid = run.id ")
            .append("LEFT JOIN validation ON validation.run_id = run.id ")
            .append("JOIN test ON test.id = run.testid ")
            .append("WHERE run.testid = ?1 ");
      if (!trashed) {
         sql.append(" AND NOT run.trashed ");
      }
      Util.addOrderBy(sql, sort, direction);
      Util.addLimitOffset(sql, limit, page);
      TestDAO test = TestDAO.find("id", testId).firstResult();
      if (test == null) {
         throw ServiceException.notFound("Cannot find test ID " + testId);
      }
      NativeQuery<Object[]> query = session.createNativeQuery(sql.toString(), Object[].class);
      query.setParameter(1, testId);
      initTypes(query);
      List<Object[]> resultList = query.getResultList();
      RunsSummary summary = new RunsSummary();
      summary.total = trashed ? RunDAO.count("testid = ?1", testId) : RunDAO.count("testid = ?1 AND trashed = false", testId);
      summary.runs = resultList.stream().map(this::createSummary).collect(Collectors.toList());
      return summary;
   }

   @PermitAll
   @WithRoles
   @WithToken
   @Override
   public RunsSummary listBySchema(String uri, Integer limit, Integer page, String sort, SortDirection direction) {
      if (uri == null || uri.isEmpty()) {
         throw ServiceException.badRequest("No `uri` query parameter given.");
      }
      StringBuilder sql = new StringBuilder("SELECT run.id, run.start, run.stop, run.testId, ")
            .append("run.owner, run.access, run.token, run.trashed, run.description, ")
            .append("run.metadata IS NOT NULL AS has_metadata, test.name AS testname, ")
            .append("'[]'::::jsonb AS schemas, '[]'::::jsonb AS datasets, '[]'::::jsonb AS validationErrors ")
            .append("FROM run_schemas rs JOIN run ON rs.runid = run.id JOIN test ON rs.testid = test.id ")
            .append("WHERE uri = ? AND NOT run.trashed");
      Util.addPaging(sql, limit, page, sort, direction);
      NativeQuery<Object[]> query = session.createNativeQuery(sql.toString(), Object[].class);
      query.setParameter(1, uri);
      initTypes(query);

      List<Object[]> runs = query.getResultList();

      RunsSummary summary = new RunsSummary();
      summary.runs = runs.stream().map(this::createSummary).collect(Collectors.toList());
      summary.total = SchemaDAO.count("uri", uri);
      return summary;
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public void trash(int id, Boolean isTrashed) {
      trashInternal(id, isTrashed == null || isTrashed);
   }

   private void trashInternal(int id, boolean trashed) {
      RunDAO run = RunDAO.findById(id);
      if (run == null) {
         throw ServiceException.notFound("Run not found: " + id);
      }
      if(run.trashed == trashed)
         throw ServiceException.badRequest("The run "+id+" has already been trashed, not possible to trash it again.");
      if (trashed) {
         trashConnectedDatasets(run.id, run.testid);
         run.trashed = trashed;
         run.persist();
         if(mediator.testMode())
            Util.registerTxSynchronization(tm, txStatus -> messageBus.publish(MessageBusChannels.RUN_TRASHED, run.testid, id));
      }
      // if the run was trashed because of a deleted test we need to ensure that the test actually exist
      // before we try to recalculate the dataset
      else {
         if(TestDAO.findById(run.testid) != null) {
            run.trashed = trashed;
            run.persistAndFlush();
            transform(id, true);
         }
         else
            throw ServiceException.badRequest("Not possible to un-trash a run that's not referenced to a Test");
      }
   }

   private void trashConnectedDatasets(int runId, int testId) {
      //Make sure to remove run_schemas as we've trashed the run
      em.createNativeQuery("DELETE FROM run_schemas WHERE runid = ?1").setParameter(1, runId).executeUpdate();
      List<DatasetDAO> datasets = DatasetDAO.list("run.id", runId);
      log.debugf("Trashing run %d (test %d, %d datasets)", runId, testId, datasets.size());
      for (var dataset : datasets) {
         mediator.propagatedDatasetDelete(dataset.id);
      }
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public void updateDescription(int id, String description) {
      // FIXME: fetchival stringifies the body into JSON string :-/
      RunDAO run = RunDAO.findById(id);
      if (run == null) {
         throw ServiceException.notFound("Run not found: " + id);
      }
      run.description = description;
      run.persistAndFlush();
   }

   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   @Override
   public Map<Integer, String> updateSchema(int id, String path, String schemaUri) {
      // FIXME: fetchival stringifies the body into JSON string :-/
      RunDAO run = RunDAO.findById(id);
      if (run == null) {
         throw ServiceException.notFound("Run not found: " + id);
      }
      String uri = Util.destringify(schemaUri);
      // Triggering dirty property on Run
      JsonNode updated = run.data.deepCopy();
      JsonNode item;
      if (updated.isObject()) {
         item = path == null ? updated : updated.path(path);
      } else if (updated.isArray()) {
         if (path == null) {
            throw ServiceException.badRequest("Cannot update root schema in an array.");
         }
         item = updated.get(Integer.parseInt(path));
      } else {
         throw ServiceException.serverError("Cannot update run data with path " + path);
      }
      if (item.isObject()) {
         if (uri != null && !uri.isEmpty()) {
            ((ObjectNode) item).set("$schema", new TextNode(uri));
         } else {
            ((ObjectNode) item).remove("$schema");
         }
      } else {
         throw ServiceException.badRequest("Cannot update schema at " + (path == null ? "<root>" : path) + " as the target is not an object");
      }
      run.data = updated;
      run.persist();
      trashConnectedDatasets(run.id, run.testid);
      Map<Integer, String> schemas =
              session.createNativeQuery("SELECT schemaid AS key, uri AS value FROM run_schemas WHERE runid = ?", Tuple.class)
                      .setParameter(1, run.id)
                      .getResultStream()
                      .collect(
                              Collectors.toMap(
                                      tuple -> ((Integer) tuple.get("key")).intValue(),
                                      tuple -> ((Integer) tuple.get("value")).toString()));

      em.flush();
      return schemas;
   }

   @WithRoles
   @Transactional
   @Override
   public List<Integer> recalculateDatasets(int runId) {
      transform(runId, true);
      return session.createNativeQuery("SELECT id FROM dataset WHERE runid = ? ORDER BY ordinal", Integer.class)
            .setParameter(1, runId).getResultList();
   }

   @RolesAllowed(Roles.ADMIN)
   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   @Override
   public void recalculateAll(String fromStr, String toStr) {
      Instant from = toInstant(fromStr);
      Instant to = toInstant(toStr);
      if (from == null || to == null) {
         throw ServiceException.badRequest("Time range is required");
      } else if (to.isBefore(from)) {
         throw ServiceException.badRequest("Time range is invalid (from > to)");
      }
      long deleted = em.createNativeQuery("DELETE FROM dataset USING run WHERE run.id = dataset.runid AND run.trashed AND run.start BETWEEN ?1 AND ?2")
            .setParameter(1, from).setParameter(2, to).executeUpdate();
      if (deleted > 0) {
         log.debugf("Deleted %d datasets for trashed runs between %s and %s", deleted, from, to);
      }

      ScrollableResults<Recalculate> results = session
                      .createNativeQuery("SELECT id, testid FROM run WHERE start BETWEEN ?1 AND ?2 AND NOT trashed ORDER BY start", Recalculate.class)
                      .setParameter(1, from).setParameter(2, to)
                      .setTupleTransformer((tuples, aliases) -> {
                         Recalculate r = new Recalculate();
                         r.runId = (int) tuples[0];
                         r.testId = (int) tuples[1];
                         return r;
                      })
                      .setReadOnly(true).setFetchSize(100)
                      .scroll(ScrollMode.FORWARD_ONLY);
      while (results.next()) {
         Recalculate r = results.get();
         log.debugf("Recalculate Datasets for run %d - forcing recalculation of all between %s and %s", r.runId, from, to);
         // transform will add proper roles anyway
//         messageBus.executeForTest(r.testId, () -> datasetService.withRecalculationLock(() -> transform(r.runId, true)));
         Util.registerTxSynchronization(tm, txStatus -> mediator.queueRunRecalculation(r.runId));
      }
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   int transform(int runId, boolean isRecalculation) {
      if (runId < 1) {
         log.errorf("Transformation parameters error: run %s", runId);
         return 0;
      }
      log.debugf("Transforming run ID %d, recalculation? %s", runId, Boolean.toString(isRecalculation));
      // We need to make sure all old datasets are gone before creating new; otherwise we could
      // break the runid,ordinal uniqueness constraint
      for (DatasetDAO old : DatasetDAO.<DatasetDAO>list("run.id", runId)) {
         for (DataPointDAO dp : DataPointDAO.<DataPointDAO>list("dataset.id", old.getInfo().id)){
            dp.delete();
         }
         mediator.propagatedDatasetDelete(old.id);
      }

      RunDAO run = RunDAO.findById(runId);
      if (run == null) {
         log.errorf("Cannot load run ID %d for transformation", runId);
         return 0;
      }
      int ordinal = 0;
      Map<Integer, JsonNode> transformerResults = new TreeMap<>();
      // naked nodes (those produced by implicit identity transformers) are all added to each dataset
      List<JsonNode> nakedNodes = new ArrayList<>();

      List<Object[]> relevantSchemas = unchecked(em.createNamedQuery(QUERY_TRANSFORMER_TARGETS)
            .setParameter(1, run.id)
            .unwrap(NativeQuery.class)
            .addScalar("type", StandardBasicTypes.INTEGER)
            .addScalar("key", StandardBasicTypes.TEXT)
            .addScalar("transformer_id", StandardBasicTypes.INTEGER)
            .addScalar("uri", StandardBasicTypes.TEXT)
            .addScalar("source", StandardBasicTypes.INTEGER)
            .getResultList() );

      int schemasAndTransformers = relevantSchemas.size();
      for (Object[] relevantSchema : relevantSchemas) {
         int type = (int) relevantSchema[0];
         String key = (String) relevantSchema[1];
         Integer transformerId = (Integer) relevantSchema[2];
         String uri = (String) relevantSchema[3];
         Integer source = (Integer) relevantSchema[4];

         TransformerDAO t;
         if (transformerId != null) {
            t = TransformerDAO.findById(transformerId);
            if (t == null) {
               log.errorf("Missing transformer with ID %d", transformerId);
            }
         } else {
            t = null;
         }
         if (t != null) {
            JsonNode root = JsonNodeFactory.instance.objectNode();
            JsonNode result;
            if (t.extractors != null && !t.extractors.isEmpty()) {
               List<Object[]> extractedData;
               try {
                  if (type == SchemaDAO.TYPE_1ST_LEVEL) {
                     // note: metadata always follow the 2nd level format
                     extractedData = unchecked(em.createNamedQuery(QUERY_1ST_LEVEL_BY_RUNID_TRANSFORMERID_SCHEMA_ID)
                           .setParameter(1, run.id).setParameter(2, transformerId)
                           .unwrap(NativeQuery.class)
                           .addScalar("name", StandardBasicTypes.TEXT)
                           .addScalar("value", JsonBinaryType.INSTANCE)
                           .getResultList());
                  } else {
                     extractedData = unchecked(em.createNamedQuery(QUERY_2ND_LEVEL_BY_RUNID_TRANSFORMERID_SCHEMA_ID)
                           .setParameter(1, run.id).setParameter(2, transformerId)
                           .setParameter(3, type == SchemaDAO.TYPE_2ND_LEVEL ? key : Integer.parseInt(key))
                           .setParameter(4, source)
                           .unwrap(NativeQuery.class)
                           .addScalar("name", StandardBasicTypes.TEXT)
                           .addScalar("value", JsonBinaryType.INSTANCE)
                           .getResultList());
                  }
               } catch (PersistenceException e) {
                  logMessage(run, PersistentLogDAO.ERROR, "Failed to extract data (JSONPath expression error?): " + Util.explainCauses(e));
                  findFailingExtractor(runId);
                  extractedData = Collections.emptyList();
               }
               addExtracted((ObjectNode) root, extractedData);
            }
            // In Horreum it's customary that when a single extractor is used we pass the result directly to the function
            // without wrapping it in an extra object.
            if (t.extractors.size() == 1) {
               if (root.size() != 1) {
                  // missing results should be null nodes
                  log.errorf("Unexpected result for single extractor: %s", root.toPrettyString());
               } else {
                  root = root.iterator().next();
               }
            }
            logMessage(run, PersistentLogDAO.DEBUG, "Run transformer %s/%s with input: <pre>%s</pre>, function: <pre>%s</pre>",
                  uri, t.name, limitLength(root.toPrettyString()), t.function);
            if (t.function != null && !t.function.isBlank()) {
               result = Util.evaluateOnce(t.function, root, Util::convertToJson,
                     (code, e) -> logMessage(run, PersistentLogDAO.ERROR,
                           "Evaluation of transformer %s/%s failed: '%s' Code: <pre>%s</pre>", uri, t.name, e.getMessage(), code),
                     output -> logMessage(run, PersistentLogDAO.DEBUG, "Output while running transformer %s/%s: <pre>%s</pre>", uri, t.name, output));
               if (result == null) {
                  // this happens upon error
                  result = JsonNodeFactory.instance.nullNode();
               }
            } else {
               result = root;
            }
            if (t.targetSchemaUri != null) {
               if (result.isObject()) {
                  putIfAbsent(run, t.targetSchemaUri, (ObjectNode) result);
               } else if (result.isArray()) {
                  ArrayNode array = (ArrayNode) result;
                  for (JsonNode node : array) {
                     if (node.isObject()) {
                        putIfAbsent(run, t.targetSchemaUri, (ObjectNode) node);
                     }
                  }
               } else {
                  result = instance.objectNode()
                        .put("$schema", t.targetSchemaUri).set("value", result);
               }
            } else if (!result.isContainerNode() || (result.isObject() && !result.has("$schema")) ||
                  (result.isArray() && StreamSupport.stream(result.spliterator(), false).anyMatch(item -> !item.has("$schema")))) {
               logMessage(run, PersistentLogDAO.WARN, "Dataset will contain element without a schema.");
            }
            JsonNode existing = transformerResults.get(transformerId);
            if (existing == null) {
               transformerResults.put(transformerId, result);
            } else if (existing.isArray()) {
               if (result.isArray()) {
                  ((ArrayNode) existing).addAll((ArrayNode) result);
               } else {
                  ((ArrayNode) existing).add(result);
               }
            } else {
               if (result.isArray()) {
                  ((ArrayNode) result).insert(0, existing);
                  transformerResults.put(transformerId, result);
               } else {
                  transformerResults.put(transformerId, instance.arrayNode().add(existing).add(result));
               }
            }
         } else {
            JsonNode node;
            JsonNode sourceNode = source == 0 ? run.data : run.metadata;
            switch (type) {
               case SchemaDAO.TYPE_1ST_LEVEL:
                  node = sourceNode;
                  break;
               case SchemaDAO.TYPE_2ND_LEVEL:
                  node = sourceNode.path(key);
                  break;
               case SchemaDAO.TYPE_ARRAY_ELEMENT:
                  node = sourceNode.path(Integer.parseInt(key));
                  break;
               default:
                  throw new IllegalStateException("Unknown type " + type);
            }
            nakedNodes.add(node);
            logMessage(run, PersistentLogDAO.DEBUG, "This test (%d) does not use any transformer for schema %s (key %s), passing as-is.", run.testid, uri, key);
         }
      }
      if (schemasAndTransformers > 0) {
         int max = transformerResults.values().stream().filter(JsonNode::isArray).mapToInt(JsonNode::size).max().orElse(1);

         for (int position = 0; position < max; position += 1) {
            ArrayNode all = instance.arrayNode(max + nakedNodes.size());
            for (var entry: transformerResults.entrySet()) {
               JsonNode node = entry.getValue();
               if (node.isObject()) {
                  all.add(node);
               } else if (node.isArray()) {
                  if (position < node.size()) {
                     all.add(node.get(position));
                  } else {
                     String message = String.format("Transformer %d produced an array of %d elements but other transformer " +
                                 "produced %d elements; dataset %d/%d might be missing some data.",
                           entry.getKey(), node.size(), max, run.id, ordinal);
                     logMessage(run, PersistentLogDAO.WARN, "%s", message);
                     log.warnf(message);
                  }
               } else {
                  logMessage(run, PersistentLogDAO.WARN, "Unexpected result provided by one of the transformers: %s", node);
                  log.warnf("Unexpected result provided by one of the transformers: %s", node);
               }
            }
            nakedNodes.forEach(all::add);
            createDataset(new DatasetDAO(run, ordinal++, run.description, all), isRecalculation);
         }
         mediator.validateRun(run.id);
         return ordinal;
      } else {
         logMessage(run, PersistentLogDAO.INFO, "No applicable schema, dataset will be empty.");
         createDataset(new DatasetDAO(
               run, 0, "Empty Dataset for run data without any schema.",
               instance.arrayNode()), isRecalculation);
         mediator.validateRun(run.id);
         return 1;
      }
   }

   private String limitLength(String str) {
      return str.length() > 1024 ? str.substring(0, 1024) + "...(truncated)" : str;
   }

   private void createDataset(DatasetDAO ds, boolean isRecalculation) {
      try {
         ds.persistAndFlush();
         mediator.newDataset(new Dataset.EventNew(DatasetMapper.from(ds), isRecalculation));
         mediator.validateDataset(ds.id);
         if(mediator.testMode())
            Util.registerTxSynchronization(tm, txStatus -> messageBus.publish(MessageBusChannels.DATASET_NEW, ds.testid, new Dataset.EventNew(DatasetMapper.from(ds), isRecalculation)));
      } catch (TransactionRequiredException tre) {
         log.error("Failed attempt to persist and send Dataset event during inactive Transaction. Likely due to prior error.", tre);
      }
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional(Transactional.TxType.REQUIRES_NEW)
   protected void logMessage(RunDAO run, int level, String format, Object... args) {
      String msg = args.length > 0 ? String.format(format, args) : format;
      new TransformationLogDAO(em.getReference(TestDAO.class, run.testid), run, level, msg).persist();
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional(Transactional.TxType.REQUIRES_NEW)
   protected void findFailingExtractor(int runId) {
      List<Object[]> extractors = session.createNativeQuery(
            "SELECT rs.uri, rs.type, rs.key, t.name, te.name AS extractor_name, te.jsonpath FROM run_schemas rs " +
            "JOIN transformer t ON t.schema_id = rs.schemaid AND t.id IN (SELECT transformer_id FROM test_transformers WHERE test_id = rs.testid) " +
            "JOIN transformer_extractors te ON te.transformer_id = t.id " +
            "WHERE rs.runid = ?1", Object[].class).setParameter(1, runId).getResultList();
      for (Object[] row : extractors) {
         try {
            int type = (int) row[1];
            // actual result of query is ignored
            if (type == SchemaDAO.TYPE_1ST_LEVEL) {
               em.createNativeQuery("SELECT jsonb_path_query_first(data, (?1)::::jsonpath)#>>'{}' FROM dataset WHERE id = ?2")
                     .setParameter(1, row[5]).setParameter(2, runId).getSingleResult();
            } else {
               em.createNativeQuery("SELECT jsonb_path_query_first(data -> (?1), (?2)::::jsonpath)#>>'{}' FROM dataset WHERE id = ?3")
                     .setParameter(1, type == SchemaDAO.TYPE_2ND_LEVEL ? row[2] : Integer.parseInt((String) row[2]))
                     .setParameter(2, row[5])
                     .setParameter(3, runId).getSingleResult();
            }
         } catch (PersistenceException e) {
            logMessage(em.getReference(RunDAO.class, runId), PersistentLogDAO.ERROR, "There seems to be an error in schema <code>%s</code> transformer <code>%s</code>, extractor <code>%s</code>, JSONPath expression <code>%s</code>: %s",
                  row[0], row[3], row[4], row[5], Util.explainCauses(e));
            return;
         }
      }
      logMessage(em.getReference(RunDAO.class, runId), PersistentLogDAO.DEBUG, "We thought there's an error in one of the JSONPaths but independent validation did not find any problems.");
   }


   @SuppressWarnings("unchecked")
   private List<Object[]> unchecked(@SuppressWarnings("rawtypes") List list) {
      return (List<Object[]>)list;
   }

   private void addExtracted(ObjectNode root, List<Object[]> resultSet) {
      for (Object[] labelValue : resultSet) {
         String name = (String)labelValue[0];
         JsonNode value = (JsonNode) labelValue[1];
         root.set(name, value);
      }
   }

   private void putIfAbsent(RunDAO run, String uri, ObjectNode node) {
      if (uri != null && !uri.isBlank() && node != null) {
         if (node.path("$schema").isMissingNode()) {
            node.put("$schema", uri);
         } else {
            logMessage(run, PersistentLogDAO.DEBUG, "<code>$schema</code> present (%s), not overriding with %s", node.path("$schema").asText(), uri);
         }
      }
   }

   class Recalculate {
      private int runId;
      private int testId;
   }

   class RunFromUri {
      private int id;
      private int testId;
   }
}
