package io.hyperfoil.tools.horreum.svc;

import io.hyperfoil.tools.horreum.api.data.Action;
import io.hyperfoil.tools.horreum.api.data.AllowedSite;
import io.hyperfoil.tools.horreum.api.data.Run;
import io.hyperfoil.tools.horreum.api.data.Test;
import io.hyperfoil.tools.horreum.bus.MessageBusChannels;
import io.hyperfoil.tools.horreum.api.alerting.Change;
import io.hyperfoil.tools.horreum.entity.data.*;
import io.hyperfoil.tools.horreum.mapper.ActionMapper;
import io.hyperfoil.tools.horreum.mapper.AllowedSiteMapper;
import io.hyperfoil.tools.horreum.api.services.ExperimentService;
import io.hyperfoil.tools.horreum.api.internal.services.ActionService;
import io.hyperfoil.tools.horreum.api.SortDirection;
import io.hyperfoil.tools.horreum.entity.ActionLogDAO;
import io.hyperfoil.tools.horreum.entity.PersistentLogDAO;
import io.hyperfoil.tools.horreum.action.ActionPlugin;
import io.hyperfoil.tools.horreum.server.EncryptionManager;
import io.hyperfoil.tools.horreum.server.WithRoles;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;

import org.jboss.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

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
   EncryptionManager encryptionManager;

   @Inject
   TestServiceImpl testService;

   @PostConstruct()
   public void postConstruct(){
      plugins = actionPlugins.stream().collect(Collectors.toMap(ActionPlugin::type, Function.identity()));
   }

   private void executeActions(MessageBusChannels event, int testId, Object payload, boolean notify){
      List<ActionDAO> actions = getActions(event.name(), testId);
      if (actions.isEmpty()) {
         new ActionLogDAO(PersistentLogDAO.DEBUG, testId, event.name(), null, "No actions found.").persist();
         return;
      }
      for (ActionDAO action : actions) {
         if (!notify && !action.runAlways) {
            log.debugf("Ignoring action for event %s in test %d, type %s as this event should not notfiy", event, testId, action.type);
            continue;
         }
         try {
            ActionPlugin plugin = plugins.get(action.type);
            if (plugin == null) {
               log.errorf("No plugin for action type %s", action.type);
               new ActionLogDAO(PersistentLogDAO.ERROR, testId, event.name(), action.type, "No plugin for action type " + action.type).persist();
               continue;
            }
            plugin.execute(action.config, action.secrets, payload).subscribe()
                  .with(item -> {}, throwable -> logActionError(testId, event.name(), action.type, throwable));
         } catch (Exception e) {
            log.errorf(e, "Failed to invoke action %d", action.id);
            new ActionLogDAO(PersistentLogDAO.ERROR, testId, event.name(), action.type, "Failed to invoke: " + e.getMessage()).persist();
            new ActionLogDAO(PersistentLogDAO.DEBUG, testId, event.name(), action.type,
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
      new ActionLogDAO(PersistentLogDAO.ERROR, testId, event, type, throwable.getMessage()).persist();
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   public void onNewTest(Test test) {
      executeActions(MessageBusChannels.TEST_NEW, -1, test, true);
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   public void onTestDelete(int testId) {
      ActionDAO.delete("testId", testId);
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   public void onNewRun(Run run) {
      Integer testId = run.testid;
      executeActions(MessageBusChannels.RUN_NEW, testId, run, true);
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   public void onNewChange(Change.Event changeEvent) {
      int testId = em.createQuery("SELECT testid FROM run WHERE id = ?1", Integer.class)
            .setParameter(1, changeEvent.dataset.runId).getResultStream().findFirst().orElse(-1);
      executeActions(MessageBusChannels.CHANGE_NEW, testId, changeEvent, changeEvent.notify);
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
         ActionDAO actionEntity = ActionMapper.to(action);
         actionEntity.persist();
         action.id = actionEntity.id;
      } else {
         ActionDAO actionEntity = ActionMapper.to(action);
         merge(actionEntity);
      }
      return action;
   }

   public JsonNode ensureNotNull(JsonNode node) {
      return node == null || node.isNull() || node.isMissingNode() ? JsonNodeFactory.instance.objectNode() : node;
   }

   void merge(ActionDAO action) {
      if (action.secrets == null || !action.secrets.isObject()) {
         action.secrets = JsonNodeFactory.instance.objectNode();
      }
      if (!action.secrets.path("modified").asBoolean(false)) {
         ActionDAO old = ActionDAO.findById(action.id);
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
      ActionDAO action =  ActionDAO.find("id", id).firstResult();
      return ActionMapper.from(action);
   }

   @Override
   @RolesAllowed(Roles.TESTER)
   @WithRoles
   @Transactional
   public Action update(Action dto) {
      if (dto.testId <= 0) {
         throw ServiceException.badRequest("Missing test id");
      }
      // just ensure the test exists
      testService.getTestForUpdate(dto.testId);
      validate(dto);

      ActionDAO action = ActionMapper.to(dto);
      if (action.id == null) {
         action.persist();
      } else {
         if (!action.active) {
            ActionDAO.deleteById(action.id);
            return null;
         } else {
            merge(action);
         }
      }
      return ActionMapper.from(action);
   }

   @WithRoles
   @RolesAllowed(Roles.ADMIN)
   @Transactional
   @Override
   public void delete(int id){
      ActionDAO.delete("id", id);
   }

   public List<ActionDAO> getActions(String event, int testId) {
      if (testId < 0) {
         return ActionDAO.find("event = ?1", event).list();
      } else {
         return ActionDAO.find("event = ?1 and (testId = ?2 or testId < 0)", event, testId).list();
      }
   }

   @RolesAllowed(Roles.ADMIN)
   @WithRoles
   @Override
   public List<Action> list(Integer limit, Integer page, String sort, SortDirection direction){
      Sort.Direction sortDirection = direction == null ? null : Sort.Direction.valueOf(direction.name());
      PanacheQuery<ActionDAO> query = ActionDAO.find("testId < 0", Sort.by(sort).direction(sortDirection));
      if (limit != null && page != null) {
         query = query.page(Page.of(page, limit));
      }
      return query.list().stream().map(ActionMapper::from).collect(Collectors.toList());
   }


   @RolesAllowed({ Roles.ADMIN, Roles.TESTER})
   @WithRoles
   @Override
   public List<Action> getTestActions(int testId) {
      List<ActionDAO> testActions = ActionDAO.list("testId", testId);
      return testActions.stream().map(ActionMapper::from).collect(Collectors.toList());
   }

   @PermitAll
   @Override
   public List<AllowedSite> allowedSites() {
      List<AllowedSiteDAO> sites = AllowedSiteDAO.listAll();
      return  sites.stream().map(AllowedSiteMapper::from).collect(Collectors.toList());
   }

   @RolesAllowed(Roles.ADMIN)
   @WithRoles
   @Transactional
   @Override
   public AllowedSite addSite(String prefix) {
      AllowedSiteDAO p = new AllowedSiteDAO();
      // FIXME: fetchival stringifies the body into JSON string :-/
      p.prefix = Util.destringify(prefix);
      em.persist(p);
      return AllowedSiteMapper.from(p);
   }

   @RolesAllowed(Roles.ADMIN)
   @WithRoles
   @Transactional
   @Override
   public void deleteSite(long id) {
      AllowedSiteDAO.delete("id", id);
   }

   @WithRoles(extras = Roles.HORREUM_SYSTEM)
   @Transactional
   public void onNewExperimentResult(ExperimentService.ExperimentResult result) {
      executeActions(MessageBusChannels.EXPERIMENT_RESULT_NEW, result.profile.testId, result, result.notify);
   }

   JsonNode exportTest(int testId) {
      ArrayNode actions = JsonNodeFactory.instance.arrayNode();
      for (ActionDAO action : ActionDAO.<ActionDAO>list("testId", testId)) {
         ObjectNode node = Util.OBJECT_MAPPER.valueToTree(ActionMapper.from(action));
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

   void importTest(int testId, JsonNode actions, boolean forceUseTestId) {
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
               ActionDAO action = ActionMapper.to(Util.OBJECT_MAPPER.treeToValue(node, Action.class));
               if(forceUseTestId)
                  action.testId = testId;
               else {
                  if (action.testId == null) {
                     action.testId = testId;
                  } else if (action.testId != testId) {
                     throw ServiceException.badRequest("Action id '" + node.path("id") + "' belongs to a different test: " + action.testId);
                  }
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
