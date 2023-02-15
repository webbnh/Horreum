package io.hyperfoil.tools.horreum.svc;

import io.hyperfoil.tools.horreum.api.ExperimentService;
import io.hyperfoil.tools.horreum.api.ActionService;
import io.hyperfoil.tools.horreum.api.SortDirection;
import io.hyperfoil.tools.horreum.bus.MessageBus;
import io.hyperfoil.tools.horreum.entity.ActionLog;
import io.hyperfoil.tools.horreum.entity.PersistentLog;
import io.hyperfoil.tools.horreum.entity.alerting.Change;
import io.hyperfoil.tools.horreum.entity.json.AllowedSite;
import io.hyperfoil.tools.horreum.entity.json.Action;
import io.hyperfoil.tools.horreum.entity.json.Run;
import io.hyperfoil.tools.horreum.entity.json.Test;
import io.hyperfoil.tools.horreum.action.ActionPlugin;
import io.hyperfoil.tools.horreum.server.EncryptionManager;
import io.hyperfoil.tools.horreum.server.WithRoles;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;

import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.transaction.Transactional;
import javax.validation.constraints.NotNull;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

@ApplicationScoped
@Startup
public class ActionServiceImpl implements ActionService {
   private static final Logger log = Logger.getLogger(ActionServiceImpl.class);

   @Inject
   Instance<ActionPlugin> actionPlugins;
   Map<String, ActionPlugin> plugins;

   @Inject
   EntityManager em;

   @Inject
   Vertx vertx;

   @Inject
   MessageBus messageBus;

   @Inject
   EncryptionManager encryptionManager;

   @PostConstruct()
   public void postConstruct(){
      plugins = actionPlugins.stream().collect(Collectors.toMap(ActionPlugin::type, Function.identity()));
      messageBus.subscribe(Test.EVENT_NEW, "ActionService", Test.class, this::onNewTest);
      messageBus.subscribe(Test.EVENT_DELETED, "ActionService", Test.class, this::onTestDelete);
      messageBus.subscribe(Run.EVENT_NEW, "ActionService", Run.class, this::onNewRun);
      messageBus.subscribe(Change.EVENT_NEW, "ActionService", Change.Event.class, this::onNewChange);
      messageBus.subscribe(ExperimentService.ExperimentResult.NEW_RESULT, "ActionService", ExperimentService.ExperimentResult.class, this::onNewExperimentResult);
   }

   private void executeActions(String event, int testId, Object payload, boolean notify){
      List<Action> actions = getActions(event, testId);
      if (actions.isEmpty()) {
         new ActionLog(PersistentLog.DEBUG, testId, event, null, "No actions found.").persist();
         return;
      }
      for (Action action : actions) {
         if (!notify && !action.runAlways) {
            log.debugf("Ignoring action for event %s in test %d, type %s as this event should not notfiy", event, testId, action.type);
            continue;
         }
         try {
            ActionPlugin plugin = plugins.get(action.type);
            if (plugin == null) {
               log.errorf("No plugin for action type %s", action.type);
               new ActionLog(PersistentLog.ERROR, testId, event, action.type, "No plugin for action type " + action.type).persist();
               continue;
            }
            plugin.execute(action.config, action.secrets, payload).subscribe()
                  .with(item -> {}, throwable -> logActionError(testId, event, action.type, throwable));
         } catch (Exception e) {
            log.errorf(e, "Failed to invoke action %d", action.id);
            new ActionLog(PersistentLog.ERROR, testId, event, action.type, "Failed to invoke: " + e.getMessage()).persist();
            new ActionLog(PersistentLog.DEBUG, testId, event, action.type,
                  "Configuration: <pre>\n<code>" + action.config.toPrettyString() +
                  "\n<code></pre>Payload: <pre>\n<code>" + Util.OBJECT_MAPPER.valueToTree(payload).toPrettyString() +
                  "</code>\n</pre>").persist();
         }
      }
   }

   void logActionError(int testId, String event, String type, Throwable throwable) {
      log.errorf("Error executing action '%s' for event %s on test %d: %s: %s",
            type, event, testId, throwable.getClass().getName(), throwable.getMessage());
      Util.executeBlocking(vertx, CachedSecurityIdentity.ANONYMOUS, Uni.createFrom().item(() -> {
         doLogActionError(testId, event, type, throwable);
         return null;
      })).subscribe().with(item -> {}, t -> {
         log.error("Cannot log error in action!", t);
         log.error("Logged error: ", throwable);
      });
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional(Transactional.TxType.REQUIRES_NEW)
   void doLogActionError(int testId, String event, String type, Throwable throwable) {
      new ActionLog(PersistentLog.ERROR, testId, event, type, throwable.getMessage()).persist();
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   public void onNewTest(Test test) {
      executeActions(Test.EVENT_NEW, -1, test, true);
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   public void onTestDelete(Test test) {
      Action.delete("test_id", test.id);
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   public void onNewRun(Run run) {
      Integer testId = run.testid;
      executeActions(Run.EVENT_NEW, testId, run, true);
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   public void onNewChange(Change.Event changeEvent) {
      int testId = em.createQuery("SELECT testid FROM run WHERE id = ?1", Integer.class)
            .setParameter(1, changeEvent.dataset.runId).getResultStream().findFirst().orElse(-1);
      executeActions(Change.EVENT_NEW, testId, changeEvent, changeEvent.notify);
   }

   void validate(Action action) {
      ActionPlugin plugin = plugins.get(action.type);
      if (plugin == null) {
         throw ServiceException.badRequest("Unknown hook type " + action.type);
      }
      plugin.validate(action.config, action.secrets);
   }

   @RolesAllowed(Roles.ADMIN)
   @WithRoles
   @Transactional
   @Override
   public Action add(Action action){
      if (action == null){
         throw ServiceException.badRequest("Send action as request body.");
      }
      if (action.id != null && action.id <= 0) {
         action.id = null;
      }
      if (action.testId == null) {
         action.testId = -1;
      }
      action.config = ensureNotNull(action.config);
      action.secrets = ensureNotNull(action.secrets);
      validate(action);
      if (action.id == null) {
         action.persist();
      } else {
         merge(action);
      }
      em.flush();
      return action;
   }

   private JsonNode ensureNotNull(@NotNull JsonNode node) {
      return node == null || node.isNull() || node.isMissingNode() ? JsonNodeFactory.instance.objectNode() : node;
   }

   void merge(Action action) {
      if (action.secrets == null || !action.secrets.isObject()) {
         action.secrets = JsonNodeFactory.instance.objectNode();
      }
      if (!action.secrets.path("modified").asBoolean(false)) {
         Action old = Action.findById(action.id);
         action.secrets = old == null ? JsonNodeFactory.instance.objectNode() : old.secrets;
      } else {
         ((ObjectNode) action.secrets).remove("modified");
      }
      em.merge(action);
   }

   @RolesAllowed(Roles.ADMIN)
   @WithRoles
   @Override
   public Action get(int id){
      return Action.find("id", id).firstResult();
   }


   @WithRoles
   @RolesAllowed(Roles.ADMIN)
   @Transactional
   @Override
   public void delete(int id){
      Action.delete("id", id);
   }

   public List<Action> getActions(String event, int testId) {
      if (testId < 0) {
         return Action.find("event = ?1", event).list();
      } else {
         return Action.find("event = ?1 and (test_id = ?2 or test_id < 0)", event, testId).list();
      }
   }

   @RolesAllowed(Roles.ADMIN)
   @WithRoles
   @Override
   public List<Action> list(Integer limit, Integer page, String sort, SortDirection direction){
      Sort.Direction sortDirection = direction == null ? null : Sort.Direction.valueOf(direction.name());
      PanacheQuery<Action> query = Action.find("test_id < 0", Sort.by(sort).direction(sortDirection));
      if (limit != null && page != null) {
         query = query.page(Page.of(page, limit));
      }
      return query.list();
   }


   @RolesAllowed({ Roles.ADMIN, Roles.TESTER})
   @WithRoles
   @Override
   public List<Action> getTestActions(int testId) {
      return Action.list("testId", testId);
   }

   @PermitAll
   @Override
   public List<AllowedSite> allowedSites() {
      return AllowedSite.listAll();
   }

   @RolesAllowed(Roles.ADMIN)
   @WithRoles
   @Transactional
   @Override
   public AllowedSite addSite(String prefix) {
      AllowedSite p = new AllowedSite();
      // FIXME: fetchival stringifies the body into JSON string :-/
      p.prefix = Util.destringify(prefix);
      em.persist(p);
      return p;
   }

   @RolesAllowed(Roles.ADMIN)
   @WithRoles
   @Transactional
   @Override
   public void deleteSite(long id) {
      AllowedSite.delete("id", id);
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   public void onNewExperimentResult(ExperimentService.ExperimentResult result) {
      executeActions(ExperimentService.ExperimentResult.NEW_RESULT, result.profile.test.id, result, result.notify);
   }

   JsonNode exportTest(int testId) {
      ArrayNode actions = JsonNodeFactory.instance.arrayNode();
      for (Action action : Action.<Action>list("test_id", testId)) {
         ObjectNode node = Util.OBJECT_MAPPER.valueToTree(action);
         if (!action.secrets.isEmpty()) {
            try {
               node.put("secrets", encryptionManager.encrypt(action.secrets.toString()));
            } catch (GeneralSecurityException e) {
               throw ServiceException.serverError("Cannot encrypt secrets for action " + action.id);
            }
         }
         actions.add(node);
      }
      return actions;
   }

   void importTest(int testId, JsonNode actions) {
      if (actions.isMissingNode() || actions.isNull()) {
         log.debugf("Import test %d: no actions");
      } else if (actions.isArray()) {
         log.debugf("Importing %d actions for test %d", actions.size(), testId);
         for (JsonNode node : actions) {
            if (!node.isObject()) {
               throw ServiceException.badRequest("Test actions must be an array of objects");
            }
            String secretsEncrypted = ((ObjectNode) node).remove("secrets").textValue();
            try {
               Action action = Util.OBJECT_MAPPER.treeToValue(node, Action.class);
               if (action.testId == null) {
                  action.testId = testId;
               } else if (action.testId != testId) {
                  throw ServiceException.badRequest("Action id '" + node.path("id") + "' belongs to a different test: " + action.testId);
               }
               if (secretsEncrypted != null) {
                  action.secrets = Util.OBJECT_MAPPER.readTree(encryptionManager.decrypt(secretsEncrypted));
               } else {
                  action.secrets = JsonNodeFactory.instance.objectNode();
               }
               em.merge(action);
            } catch (JsonProcessingException e) {
               throw ServiceException.badRequest("Cannot deserialize action id '" + node.path("id").asText() + "': " + e.getMessage());
            } catch (GeneralSecurityException e) {
               throw ServiceException.badRequest("Cannot decrypt secrets for action id '" + node.path("id").asText() + "': " + e.getMessage());
            }
         }
      } else {
         throw ServiceException.badRequest("Actions are invalid: " + actions.getNodeType());
      }
   }
}
