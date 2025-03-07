package io.hyperfoil.tools.horreum.svc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hyperfoil.tools.horreum.api.alerting.Change;
import io.hyperfoil.tools.horreum.api.alerting.DataPoint;
import io.hyperfoil.tools.horreum.api.data.Action;
import io.hyperfoil.tools.horreum.api.data.Dataset;
import io.hyperfoil.tools.horreum.api.data.Run;
import io.hyperfoil.tools.horreum.api.data.Test;
import io.hyperfoil.tools.horreum.api.services.ExperimentService;
import io.hyperfoil.tools.horreum.entity.data.ActionDAO;
import io.hyperfoil.tools.horreum.entity.data.SchemaDAO;
import io.hyperfoil.tools.horreum.events.DatasetChanges;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;

@ApplicationScoped
public class ServiceMediator {

    @Inject
    private TestServiceImpl testService;

    @Inject
    private AlertingServiceImpl alertingService;

    @Inject
    private RunServiceImpl runService;

    @Inject
    private ReportServiceImpl reportService;

    @Inject
    private ExperimentServiceImpl experimentService;

    @Inject
    private LogServiceImpl logService;

    @Inject
    private SubscriptionServiceImpl subscriptionService;

    @Inject
    private ActionServiceImpl actionService;

    @Inject
    private NotificationServiceImpl notificationService;

    @Inject
    private DatasetServiceImpl datasetService;

    @Inject
    private EventAggregator aggregator;

    @Inject
    Vertx vertx;
    @Inject
    private SchemaServiceImpl schemaService;

    @Inject
    @ConfigProperty(name = "horreum.test-mode", defaultValue = "false")
    private Boolean testMode;

    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 10000)
    @Channel("dataset-event-out")
    Emitter<Dataset.EventNew> dataSetEmitter;

    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 10000)
    @Channel("run-recalc-out")
    Emitter<Integer> runEmitter;

    public ServiceMediator() {
    }

    void executeBlocking(Runnable runnable) {
        Util.executeBlocking(vertx, runnable);
    }

    boolean testMode() {
        return testMode;
    }

    @Transactional
    void newTest(Test test) {
        actionService.onNewTest(test);
    }

    @Transactional
    void deleteTest(int testId) {
        // runService will call mediator.propagatedDatasetDelete which needs
        // to be completed before we call the other services
        runService.onTestDeleted(testId);
        actionService.onTestDelete(testId);
        alertingService.onTestDeleted(testId);
        experimentService.onTestDeleted(testId);
        logService.onTestDelete(testId);
        reportService.onTestDelete(testId);
        subscriptionService.onTestDelete(testId);
    }

    @Transactional
    void newRun(Run run) {
        actionService.onNewRun(run);
        alertingService.removeExpected(run);
    }

    @Transactional
    void propagatedDatasetDelete(int datasetId) {
        //make sure to delete the entities that has a reference on dataset first
        alertingService.onDatasetDeleted(datasetId);
        datasetService.deleteDataset(datasetId);
    }

    @Transactional
    void updateLabels(Dataset.LabelsUpdatedEvent event) {
        alertingService.onLabelsUpdated(event);
    }

    void newDataset(Dataset.EventNew eventNew) {
        //Note: should we call onNewDataset which will enable a lock?
        datasetService.onNewDataset(eventNew);
    }

    @Transactional
    void newChange(Change.Event event) {
        actionService.onNewChange(event);
        aggregator.onNewChange(event);
    }

    @Incoming("dataset-event-in")
    @Blocking(ordered = false, value = "horreum.dataset.pool")
    @ActivateRequestContext
    public void processDatasetEvents(Dataset.EventNew newEvent) {
            datasetService.onNewDatasetNoLock(newEvent);
            validateDataset(newEvent.datasetId);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    void queueDatasetEvents(Dataset.EventNew event) {
        dataSetEmitter.send(event);
    }
    @Incoming("run-recalc-in")
    @Blocking(ordered = false, value = "horreum.run.pool")
    @ActivateRequestContext
    public void processRunRecalculation(int runId) {
            runService.transform(runId, true);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    void queueRunRecalculation(int runId) {
        runEmitter.send(runId);
    }

    void dataPointsProcessed(DataPoint.DatasetProcessedEvent event) {
        experimentService.onDatapointsCreated(event);
    }

    void missingValuesDataset(MissingValuesEvent event) {
        notificationService.onMissingValues(event);
    }
    void newDatasetChanges(DatasetChanges changes) {
        notificationService.onNewChanges(changes);
    }
    int transform(int runId, boolean isRecalculation) {
        return runService.transform(runId, isRecalculation);
    }
    void withRecalculationLock(Runnable run) {
        datasetService.withRecalculationLock(run);
    }
    void newExperimentResult(ExperimentService.ExperimentResult result) {
        actionService.onNewExperimentResult(result);
    }
    void validate(Action dto) {
        actionService.validate(dto);
    }

    void merge(ActionDAO dao) {
        actionService.merge(dao);
    }

    void exportTest(ObjectNode export, int testId) {
        export.set("alerting", alertingService.exportTest(testId));
        export.set("actions", actionService.exportTest(testId));
        export.set("experiments", experimentService.exportTest(testId));
        export.set("subscriptions", subscriptionService.exportSubscriptions(testId));
    }

    @Transactional
    void importTestToAll(Integer id, JsonNode alerting, JsonNode actions, JsonNode experiments,
                         JsonNode subscriptions, boolean forceUseTestId) {
        if(alerting != null)
            alertingService.importTest(id, alerting, forceUseTestId);
        if(actions != null)
            actionService.importTest(id, actions, forceUseTestId);
        if(experiments != null)
            experimentService.importTest(id, experiments, forceUseTestId);
        if(subscriptions != null)
            subscriptionService.importSubscriptions(id, subscriptions);
    }

    public void newOrUpdatedSchema(SchemaDAO schema) {
        runService.processNewOrUpdatedSchema(schema);
    }
    public void updateFingerprints(int testId) {
        datasetService.updateFingerprints(testId);
    }

    public void validateRun(Integer runId) {
        schemaService.validateRunData(runId, null);
    }
    public void validateDataset(Integer datasetId) {
        schemaService.validateDatasetData(datasetId, null);
    }
    public void validateSchema(int schemaId) {
        schemaService.revalidateAll(schemaId);
    }
}
