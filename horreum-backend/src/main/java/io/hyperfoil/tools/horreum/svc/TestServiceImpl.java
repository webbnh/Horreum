package io.hyperfoil.tools.horreum.svc;

import io.hyperfoil.tools.horreum.api.TestService;
import io.hyperfoil.tools.horreum.entity.json.*;
import io.hyperfoil.tools.horreum.server.WithRoles;
import io.hyperfoil.tools.horreum.server.WithToken;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.eventbus.EventBus;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import javax.servlet.http.HttpServletResponse;
import javax.transaction.TransactionManager;
import javax.transaction.Transactional;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.hibernate.Hibernate;
import org.hibernate.transform.Transformers;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

@WithRoles
public class TestServiceImpl implements TestService {
   private static final Logger log = Logger.getLogger(TestServiceImpl.class);

   private static final String UPDATE_NOTIFICATIONS = "UPDATE test SET notificationsenabled = ? WHERE id = ?";
   private static final String CHANGE_ACCESS = "UPDATE test SET owner = ?, access = ? WHERE id = ?";
   private static final String TRASH_RUNS = "UPDATE run SET trashed = true WHERE testid = ?";

   @Inject
   EntityManager em;

   @Inject
   TransactionManager tm;

   @Inject
   EventBus eventBus;

   @Inject
   SecurityIdentity identity;

   @Context
   HttpServletResponse response;

   @Override
   @RolesAllowed(Roles.TESTER)
   @Transactional
   public void delete(Integer id){
      Test test = Test.findById(id);
      if (test == null) {
         throw ServiceException.notFound("No test with id " + id);
      } else if (!identity.getRoles().contains(test.owner)) {
         throw ServiceException.forbidden("You are not an owner of test " + id);
      }
      test.defaultView = null;
      em.merge(test);
      View.find("test_id", id).stream().forEach(view -> {
         ViewComponent.delete("view_id", ((View) view).id);
         view.delete();
      });
      test.delete();
      em.createNativeQuery(TRASH_RUNS).setParameter(1, test.id).executeUpdate();
      Util.publishLater(tm, eventBus, Test.EVENT_DELETED, test);
   }

   @Override
   @WithToken
   @PermitAll
   public Test get(Integer id, String token){
      Test test = Test.find("id", id).firstResult();
      if (test == null) {
         throw ServiceException.notFound("No test with id " + id);
      }
      Hibernate.initialize(test.tokens);
      return test;
   }

   @Override
   public Test getByNameOrId(String input){
      Test test;
      if (input.matches("-?\\d+")) {
         int id = Integer.parseInt(input);
         test =  Test.find("name = ?1 or id = ?2", input, id).firstResult();
      } else {
         test =  Test.find("name", input).firstResult();
      }
      if (test == null) {
         throw ServiceException.notFound("No test with name " + input);
      }
      return test;
   }

   @Override
   @RolesAllowed(Roles.TESTER)
   @Transactional
   public Test add(Test test){
      if (!identity.hasRole(test.owner)) {
         throw ServiceException.forbidden("This user does not have the " + test.owner + " role!");
      }
      addAuthenticated(test);
      Hibernate.initialize(test.tokens);
      return test;
   }

   void addAuthenticated(Test test) {
      Test existing = Test.find("id", test.id).firstResult();
      if (test.id != null && test.id <= 0) {
         test.id = null;
      }
      if (test.notificationsEnabled == null) {
         test.notificationsEnabled = true;
      }
      test.folder = normalizeFolderName(test.folder);
      if ("*".equals(test.folder)) {
         throw new IllegalArgumentException("Illegal folder name '*': this is used as wildcard.");
      }
      test.ensureLinked();
      if (existing != null) {
         if (!identity.hasRole(existing.owner)) {
            throw ServiceException.forbidden("This user does not have the " + existing.owner + " role!");
         }
         // We're not updating view using this method
         if (test.defaultView == null) {
            test.defaultView = existing.defaultView;
         }
         test.tokens = existing.tokens;
         test.copyIds(existing);

         em.merge(test);
      } else {
         em.persist(test);
         if (test.defaultView != null) {
            em.persist(test.defaultView);
         } else {
            View view = new View();
            view.name = "default";
            view.components = Collections.emptyList();
            view.test = test;
            em.persist(view);
            test.defaultView = view;
         }
         try {
            em.flush();
         } catch (PersistenceException e) {
            if (e.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
               throw new ServiceException(Response.Status.CONFLICT, "Could not persist test due to another test.");
            } else {
               throw new WebApplicationException(e, Response.serverError().build());
            }
         }
         Util.publishLater(tm, eventBus, Test.EVENT_NEW, test);
         response.setStatus(Response.Status.CREATED.getStatusCode());
      }
   }

   @Override
   @PermitAll
   public List<Test> list(String roles, Integer limit, Integer page, String sort, Sort.Direction direction){
      PanacheQuery<Test> query;
      Set<String> actualRoles = null;
      if (Roles.hasRolesParam(roles)) {
         if (roles.equals("__my")) {
            if (!identity.isAnonymous()) {
               actualRoles = identity.getRoles();
            }
         } else {
            actualRoles = new HashSet<>(Arrays.asList(roles.split(";")));
         }
      }

      Sort sortOptions = Sort.by(sort).direction(direction);
      if (actualRoles == null) {
         query = Test.findAll(sortOptions);
      } else {
         query = Test.find("owner IN ?1", sortOptions, actualRoles);
      }
      if (limit != null && page != null) {
         query.page(Page.of(page, limit));
      }
      return query.list();
   }

   @Override
   @PermitAll
   public TestListing summary(String roles, String folder) {
      folder = normalizeFolderName(folder);
      StringBuilder testSql = new StringBuilder();
      // TODO: materialize the counts in a table for quicker lookup
      testSql.append("WITH runs AS (SELECT testid, count(id) as count FROM run WHERE run.trashed = false OR run.trashed IS NULL GROUP BY testid), ");
      testSql.append("datasets AS (SELECT testid, count(id) as count FROM dataset GROUP BY testid) ");
      testSql.append("SELECT test.id,test.name,test.folder,test.description, COALESCE(datasets.count, 0) AS datasets, COALESCE(runs.count, 0) AS runs,test.owner,test.access ");
      testSql.append("FROM test LEFT JOIN runs ON runs.testid = test.id LEFT JOIN datasets ON datasets.testid = test.id");
      boolean anyFolder = "*".equals(folder);
      if (anyFolder) {
         Roles.addRolesSql(identity, "test", testSql, roles, 1, " WHERE");
      } else {
         testSql.append(" WHERE COALESCE(folder, '') = COALESCE((?1)::::text, '')");
         Roles.addRolesSql(identity, "test", testSql, roles, 2, " AND");
      }
      testSql.append(" ORDER BY test.name");
      Query testQuery = em.createNativeQuery(testSql.toString());
      if (anyFolder) {
         Roles.addRolesParam(identity, testQuery, 1, roles);
      } else {
         testQuery.setParameter(1, folder);
         Roles.addRolesParam(identity, testQuery, 2, roles);
      }
      SqlServiceImpl.setResultTransformer(testQuery, Transformers.aliasToBean(TestSummary.class));

      TestListing listing = new TestListing();
      //noinspection unchecked
      listing.tests = testQuery.getResultList();
      return listing;
   }

   private static String normalizeFolderName(String folder) {
      if (folder == null) {
         return null;
      }
      if (folder.endsWith("/")) {
         folder = folder.substring(0, folder.length() - 1);
      }
      folder = folder.trim();
      if (folder.isEmpty()) {
         folder = null;
      }
      return folder;
   }

   @Override
   @PermitAll
   public List<String> folders(String roles) {
      StringBuilder sql = new StringBuilder("SELECT DISTINCT folder FROM test");
      Roles.addRolesSql(identity, "test", sql, roles, 1, " WHERE");
      Query query = em.createNativeQuery(sql.toString());
      Roles.addRolesParam(identity, query, 1, roles);
      Set<String> result = new HashSet<>();
      @SuppressWarnings("unchecked")
      List<String> folders = query.getResultList();
      for (String folder : folders) {
         if (folder == null || folder.isEmpty()) {
            continue;
         }
         int index = -1;
         for (;;) {
            index = folder.indexOf('/', index + 1);
            if (index >= 0) {
               result.add(folder.substring(0, index));
            } else {
               result.add(folder);
               break;
            }
         }
      }
      folders = new ArrayList<>(result);
      folders.sort(String::compareTo);
      folders.add(0, null);
      return folders;
   }

   @Override
   @RolesAllowed("tester")
   @Transactional
   public Integer addToken(Integer testId, TestToken token) {
      if (token.hasUpload() && !token.hasRead()) {
         throw ServiceException.badRequest("Upload permission requires read permission as well.");
      }
      Test test = getTestForUpdate(testId);
      token.test = test;
      test.tokens.add(token);
      test.persistAndFlush();
      return token.id;
   }

   @Override
   @RolesAllowed("tester")
   public Collection<TestToken> tokens(Integer testId) {
      Test t = Test.findById(testId);
      Hibernate.initialize(t.tokens);
      return t.tokens;
   }

   @Override
   @RolesAllowed("tester")
   @Transactional
   public void dropToken(Integer testId, Integer tokenId) {
      Test test = getTestForUpdate(testId);
      test.tokens.removeIf(t -> Objects.equals(t.id, tokenId));
      test.persist();
   }

   @Override
   @RolesAllowed("tester")
   @Transactional
   // TODO: it would be nicer to use @FormParams but fetchival on client side doesn't support that
   public void updateAccess(Integer id,
                            String owner,
                            Access access) {
      Query query = em.createNativeQuery(CHANGE_ACCESS);
      query.setParameter(1, owner);
      query.setParameter(2, access.ordinal());
      query.setParameter(3, id);
      if (query.executeUpdate() != 1) {
         throw ServiceException.serverError("Access change failed (missing permissions?)");
      }
   }

   @Override
   @RolesAllowed("tester")
   @Transactional
   public void updateView(Integer testId, View view) {
      if (testId == null || testId <= 0) {
         throw ServiceException.badRequest("Missing test id");
      }
      try {
         Test test = getTestForUpdate(testId);
         view.ensureLinked();
         view.test = test;
         if (test.defaultView != null) {
            view.copyIds(test.defaultView);
         }
         if (view.id == null) {
            em.persist(view);
         } else {
            test.defaultView = em.merge(view);
         }
         test.persist();
         em.flush();
      } catch (PersistenceException e) {
         log.error("Failed to persist updated view", e);
         throw ServiceException.badRequest("Failed to persist the view. It is possible that some schema extractors used in this view do not use valid JSON paths.");
      }
   }

   @Override
   @RolesAllowed("tester")
   @Transactional
   public void updateAccess(Integer id,
                            boolean enabled) {
      Query query = em.createNativeQuery(UPDATE_NOTIFICATIONS)
            .setParameter(1, enabled)
            .setParameter(2, id);
      if (query.executeUpdate() != 1) {
         throw ServiceException.serverError("Access change failed (missing permissions?)");
      }
   }

   @RolesAllowed("tester")
   @WithRoles
   @Transactional
   @Override
   public void updateFolder(Integer id, String folder) {
      if ("*".equals(folder)) {
         throw new IllegalArgumentException("Illegal folder name '*': this is used as wildcard.");
      }
      Test test = getTestForUpdate(id);
      test.folder = normalizeFolderName(folder);
      test.persist();
   }

   @Override
   @RolesAllowed("tester")
   @Transactional
   public void updateHook(Integer testId, Hook hook) {
      if (testId == null || testId <= 0) {
         throw ServiceException.badRequest("Missing test id");
      }
      Test test = getTestForUpdate(testId);
      hook.target = testId;

      if (hook.id == null) {
         em.persist(hook);
      } else {
         if (!hook.active) {
            Hook toDelete = em.find(Hook.class, hook.id);
            em.remove(toDelete);
         } else {
            em.merge(hook);
         }
      }
      test.persist();
   }

   @WithRoles
   @Override
   public List<JsonNode> listFingerprints(int testId) {
      @SuppressWarnings("unchecked") Stream<String> stream = em.createNativeQuery(
            "SELECT DISTINCT fingerprint::::text FROM fingerprint fp " +
            "JOIN dataset ON dataset.id = dataset_id WHERE dataset.testid = ?1")
         .setParameter(1, testId).getResultStream();
      return stream.map(Util::toJsonNode).collect(Collectors.toList());
   }

   @WithRoles
   @Transactional
   @Override
   public void updateTransformers(Integer testId, List<Integer> transformerIds) {
      if (transformerIds == null) {
         throw ServiceException.badRequest("Null transformer IDs");
      }
      Test test = getTestForUpdate(testId);
      test.transformers.clear();
      test.transformers.addAll(Transformer.list("id IN ?1", transformerIds));
      test.persistAndFlush();
   }

   @WithRoles
   @Transactional
   @Override
   public void updateFingerprint(int testId, FingerprintUpdate update) {
      Test test = getTestForUpdate(testId);
      test.fingerprintLabels = update.labels.stream().reduce(JsonNodeFactory.instance.arrayNode(), ArrayNode::add, ArrayNode::addAll);
      // In case the filter is null we need to force the property to be dirty
      test.fingerprintFilter = "";
      test.fingerprintFilter = update.filter;
      test.persistAndFlush();
   }

   private Test getTestForUpdate(int testId) {
      Test test = Test.findById(testId);
      if (test == null) {
         throw ServiceException.notFound("Test " + testId + " was not found");
      }
      if (!identity.hasRole(test.owner) || !identity.hasRole(test.owner.substring(0, test.owner.length() - 4) + "tester")) {
         throw ServiceException.forbidden("This user is not an owner/tester for " + test.owner);
      }
      return test;
   }
}
