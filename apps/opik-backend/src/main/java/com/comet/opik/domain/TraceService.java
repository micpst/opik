package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.DeleteTraceThreads;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceCountResponse;
import com.comet.opik.api.TraceDetails;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.IdentifierMismatchException;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.api.sorting.TraceThreadSortingFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.AsyncUtils;
import com.comet.opik.utils.BinaryOperatorUtils;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.api.Trace.TracePage;
import static com.comet.opik.api.TraceThread.TraceThreadPage;
import static com.comet.opik.infrastructure.DatabaseUtils.ANALYTICS_DELETE_BATCH_SIZE;
import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@ImplementedBy(TraceServiceImpl.class)
public interface TraceService {

    String PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH = "Project name and workspace name do not match the existing trace";

    Mono<UUID> create(Trace trace);

    Mono<Long> create(TraceBatch batch);

    Mono<Void> update(TraceUpdate trace, UUID id);

    Mono<Trace> get(UUID id);

    Mono<TraceDetails> getTraceDetailsById(UUID id);

    Mono<Void> delete(Set<UUID> ids, UUID projectId);

    Mono<TracePage> find(int page, int size, TraceSearchCriteria criteria);

    Mono<Boolean> validateTraceWorkspace(String workspaceId, Set<UUID> traceIds);

    Mono<TraceCountResponse> countTracesPerWorkspace();

    Mono<BiInformationResponse> getTraceBIInformation();

    Mono<ProjectStats> getStats(TraceSearchCriteria searchCriteria);

    Mono<Long> getDailyCreatedCount();

    Mono<Map<UUID, Instant>> getLastUpdatedTraceAt(Set<UUID> projectIds, String workspaceId);

    Mono<TraceThreadPage> getTraceThreads(int page, int size, TraceSearchCriteria criteria);

    Mono<Void> deleteTraceThreads(DeleteTraceThreads traceThreads);

    Mono<TraceThread> getThreadById(UUID projectId, String threadId);

    Flux<Trace> search(int limit, TraceSearchCriteria searchCriteria);

    Mono<Long> countTraces(Set<UUID> projectIds);

    Flux<TraceThread> threadsSearch(int limit, @NonNull TraceSearchCriteria criteria);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TraceServiceImpl implements TraceService {

    public static final String TRACE_KEY = "Trace";

    private final @NonNull TraceDAO dao;
    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull LockService lockService;
    private final @NonNull EventBus eventBus;
    private final @NonNull TraceThreadSortingFactory traceThreadSortingFactory;
    private final @NonNull TraceSortingFactory traceSortingFactory;

    @Override
    @WithSpan
    public Mono<UUID> create(@NonNull Trace trace) {

        String projectName = WorkspaceUtils.getProjectName(trace.projectName());
        UUID id = trace.id() == null ? idGenerator.generateId() : trace.id();

        return Mono.deferContextual(ctx -> IdGenerator
                .validateVersionAsync(id, TRACE_KEY)
                .then(Mono.defer(() -> projectService.getOrCreate(projectName)))
                .flatMap(project -> lockService.executeWithLock(
                        new LockService.Lock(id, TRACE_KEY),
                        Mono.defer(() -> insertTrace(trace, project, id)))
                        .doOnSuccess(__ -> {
                            var savedTrace = trace.toBuilder().projectId(project.id()).projectName(projectName).build();
                            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                            String userName = ctx.get(RequestContext.USER_NAME);

                            eventBus.post(new TracesCreated(List.of(savedTrace), workspaceId, userName));
                        })));
    }

    @WithSpan
    public Mono<Long> create(TraceBatch batch) {

        Preconditions.checkArgument(!batch.traces().isEmpty(), "Batch traces cannot be empty");

        List<String> projectNames = batch.traces()
                .stream()
                .map(Trace::projectName)
                .map(WorkspaceUtils::getProjectName)
                .distinct()
                .toList();

        return Mono.deferContextual(ctx -> {
            Mono<List<Trace>> resolveProjects = Flux.fromIterable(projectNames)
                    .flatMap(projectService::getOrCreate)
                    .collectList()
                    .map(projects -> bindTraceToProjectAndId(batch, projects))
                    .subscribeOn(Schedulers.boundedElastic());

            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return resolveProjects
                    .flatMap(traces -> template.nonTransaction(connection -> dao.batchInsert(traces, connection))
                            .doOnSuccess(__ -> eventBus.post(new TracesCreated(traces, workspaceId, userName))));
        });
    }

    private List<Trace> bindTraceToProjectAndId(TraceBatch batch, List<Project> projects) {
        Map<String, Project> projectPerName = projects.stream()
                .collect(Collectors.toMap(
                        Project::name,
                        Function.identity(),
                        BinaryOperatorUtils.last(),
                        () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));

        return batch.traces()
                .stream()
                .map(trace -> {
                    String projectName = WorkspaceUtils.getProjectName(trace.projectName());
                    Project project = projectPerName.get(projectName);

                    UUID id = trace.id() == null ? idGenerator.generateId() : trace.id();
                    IdGenerator.validateVersion(id, TRACE_KEY);

                    return trace.toBuilder().id(id).projectId(project.id()).projectName(project.name()).build();
                })
                .toList();
    }

    private Mono<UUID> insertTrace(Trace newTrace, Project project, UUID id) {
        return dao.getPartialById(id)
                .flatMap(existingTrace -> insertTrace(newTrace, project, id, existingTrace))
                .switchIfEmpty(Mono.defer(() -> create(newTrace, project, id)))
                .onErrorResume(this::handleDBError);
    }

    private <T> Mono<T> handleDBError(Throwable ex) {
        if (ex instanceof ClickHouseException
                && ex.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && ex.getMessage().contains("String too long for type FixedString")
                && (ex.getMessage().contains("project_id") || ex.getMessage().contains("workspace_id"))) {

            return failWithConflict(PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH);
        }

        return Mono.error(ex);
    }

    private Mono<Project> getProjectById(TraceUpdate traceUpdate) {
        return AsyncUtils.makeMonoContextAware((userName, workspaceId) -> {

            if (traceUpdate.projectId() != null) {
                return Mono.fromCallable(() -> projectService.get(traceUpdate.projectId(), workspaceId));
            }

            return Mono.empty();
        });
    }

    private Mono<UUID> insertTrace(Trace newTrace, Project project, UUID id, Trace existingTrace) {
        return Mono.defer(() -> {
            // check if a partial trace exists caused by a patch request
            if (existingTrace.startTime().equals(Instant.EPOCH)
                    && existingTrace.projectId().equals(project.id())) {

                return create(newTrace, project, id);
            }

            if (!project.id().equals(existingTrace.projectId())) {
                return failWithConflict(PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH);
            }

            // otherwise, reject the trace creation
            return Mono
                    .error(new EntityAlreadyExistsException(new ErrorMessage(List.of("Trace already exists"))));
        });
    }

    private Mono<UUID> create(Trace trace, Project project, UUID id) {
        return template.nonTransaction(connection -> {
            var newTrace = trace.toBuilder().id(id).projectId(project.id()).build();
            return dao.insert(newTrace, connection);
        });
    }

    @Override
    @WithSpan
    public Mono<Void> update(@NonNull TraceUpdate traceUpdate, @NonNull UUID id) {

        var projectName = WorkspaceUtils.getProjectName(traceUpdate.projectName());

        return Mono.deferContextual(ctx -> getProjectById(traceUpdate)
                .switchIfEmpty(Mono.defer(() -> projectService.getOrCreate(projectName)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(project -> lockService.executeWithLock(
                        new LockService.Lock(id, TRACE_KEY),
                        Mono.defer(() -> dao.getPartialById(id)
                                .flatMap(trace -> updateOrFail(traceUpdate, id, trace, project).thenReturn(id))
                                .switchIfEmpty(Mono.defer(() -> insertUpdate(project, traceUpdate, id))
                                        .thenReturn(id))
                                .onErrorResume(this::handleDBError)
                                .doOnSuccess(__ -> eventBus.post(new TracesUpdated(
                                        Set.of(project.id()),
                                        ctx.get(RequestContext.WORKSPACE_ID),
                                        ctx.get(RequestContext.USER_NAME)))))))
                .then());
    }

    private Mono<Void> insertUpdate(Project project, TraceUpdate traceUpdate, UUID id) {
        return IdGenerator
                .validateVersionAsync(id, TRACE_KEY)
                .then(Mono.defer(() -> template.nonTransaction(
                        connection -> dao.partialInsert(project.id(), traceUpdate, id, connection))));
    }

    private Mono<Void> updateOrFail(TraceUpdate traceUpdate, UUID id, Trace trace, Project project) {
        if (project.id().equals(trace.projectId())) {
            return template.nonTransaction(connection -> dao.update(traceUpdate, id, connection));
        }

        return failWithConflict(PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH);
    }

    private Mono<Project> getProjectByName(String projectName) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> projectService.findByNames(workspaceId, List.of(projectName)))
                    .flatMap(projects -> projects.stream().findFirst().map(Mono::just).orElseGet(Mono::empty))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    private Mono<TraceSearchCriteria> findProjectAndVerifyVisibility(TraceSearchCriteria criteria) {
        return projectService.resolveProjectIdAndVerifyVisibility(criteria.projectId(), criteria.projectName())
                .map(projectId -> criteria.toBuilder()
                        .projectId(projectId)
                        .build());
    }

    private <T> Mono<T> failWithConflict(String error) {
        log.info(error);
        return Mono.error(new IdentifierMismatchException(new ErrorMessage(List.of(error))));
    }

    @Override
    @WithSpan
    public Mono<Trace> get(@NonNull UUID id) {
        return template.nonTransaction(connection -> dao.findById(id, connection))
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Trace", id))));
    }

    @Override
    public Mono<TraceDetails> getTraceDetailsById(UUID id) {
        return template.nonTransaction(connection -> dao.getTraceDetailsById(id, connection))
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Trace", id.toString()))));
    }

    @Override
    @WithSpan
    public Mono<Void> delete(@NonNull Set<UUID> ids, UUID projectId) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");
        log.info("Deleting traces, count '{}'", ids.size());
        return template.nonTransaction(connection -> delete(ids, projectId, connection));
    }

    private Mono<Void> delete(Set<UUID> ids, UUID projectId, Connection connection) {
        return Mono.deferContextual(
                ctx -> Flux.fromIterable(Lists.partition(new ArrayList<>(ids), ANALYTICS_DELETE_BATCH_SIZE))
                        .flatMap(batch -> dao.delete(Set.copyOf(batch), projectId, connection)
                                .doOnSuccess(__ -> {
                                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                                    String userName = ctx.get(RequestContext.USER_NAME);
                                    eventBus.post(TracesDeleted.builder()
                                            .traceIds(Set.copyOf(batch))
                                            .projectId(projectId)
                                            .workspaceId(workspaceId)
                                            .userName(userName)
                                            .build());
                                    log.info("Published TracesDeleted event for trace ids count '{}' on workspace '{}'",
                                            batch.size(), workspaceId);
                                }))
                        .then());
    }

    @Override
    @WithSpan
    public Mono<TracePage> find(int page, int size, @NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMap(resolvedCriteria -> template
                        .nonTransaction(connection -> dao.find(size, page, resolvedCriteria, connection)))
                .switchIfEmpty(Mono.just(TracePage.empty(page, traceSortingFactory.getSortableFields())));
    }

    @Override
    @WithSpan
    public Mono<Boolean> validateTraceWorkspace(@NonNull String workspaceId, @NonNull Set<UUID> traceIds) {
        if (traceIds.isEmpty()) {
            return Mono.just(true);
        }

        return template.nonTransaction(connection -> dao.getTraceWorkspace(traceIds, connection)
                .map(traceWorkspace -> traceWorkspace.stream()
                        .allMatch(trace -> workspaceId.equals(trace.workspaceId()))));
    }

    @Override
    @WithSpan
    public Mono<TraceCountResponse> countTracesPerWorkspace() {

        return template.stream(dao::countTracesPerWorkspace)
                .collectList()
                .flatMap(items -> Mono.just(
                        TraceCountResponse.builder()
                                .workspacesTracesCount(items)
                                .build()))
                .switchIfEmpty(Mono.just(TraceCountResponse.empty()));
    }

    @Override
    @WithSpan
    public Mono<BiInformationResponse> getTraceBIInformation() {
        log.info("Getting trace BI events daily data");
        return template.stream(dao::getTraceBIInformation)
                .collectList()
                .flatMap(items -> Mono.just(
                        BiInformationResponse.builder()
                                .biInformation(items)
                                .build()))
                .switchIfEmpty(Mono.just(BiInformationResponse.empty()));
    }

    @Override
    @WithSpan
    public Mono<ProjectStats> getStats(@NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMap(dao::getStats)
                .switchIfEmpty(Mono.just(ProjectStats.empty()));
    }

    @Override
    @WithSpan
    public Mono<Long> getDailyCreatedCount() {
        Mono<List<UUID>> projects = Mono
                .fromCallable(() -> projectService.findByNames(ProjectService.DEFAULT_WORKSPACE_ID, DemoData.PROJECTS)
                        .stream()
                        .map(Project::id)
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());

        return projects.switchIfEmpty(Mono.just(List.of())).flatMap(dao::getDailyTraces);
    }

    @Override
    public Mono<Map<UUID, Instant>> getLastUpdatedTraceAt(Set<UUID> projectIds, String workspaceId) {
        return template
                .nonTransaction(connection -> dao.getLastUpdatedTraceAt(projectIds, workspaceId, connection));
    }

    @Override
    public Mono<TraceThreadPage> getTraceThreads(int page, int size, @NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMap(it -> dao.findThreads(size, page, it))
                .switchIfEmpty(Mono.just(TraceThreadPage.empty(page, traceThreadSortingFactory.getSortableFields())));
    }

    @Override
    public Mono<Void> deleteTraceThreads(@NonNull DeleteTraceThreads traceThreads) {
        if (traceThreads.projectId() == null && traceThreads.projectName() == null) {
            return Mono.error(new ClientErrorException("must provide either a project_name or a project_id",
                    HttpStatus.SC_UNPROCESSABLE_ENTITY));
        }

        if (traceThreads.projectId() != null) {
            return deleteTraceThreadsByProjectId(traceThreads.projectId(), traceThreads.threadIds());
        }

        return getProjectByName(traceThreads.projectName())
                .flatMap(project -> deleteTraceThreadsByProjectId(project.id(), traceThreads.threadIds()));
    }

    private Mono<Void> deleteTraceThreadsByProjectId(@NonNull UUID projectId, @NonNull List<String> threadIds) {
        log.info("Deleting trace threads by project id '{}' and thread ids count '{}'", projectId, threadIds.size());

        return Mono.deferContextual(ctx -> template.nonTransaction(connection ->
        // First get all trace IDs for the thread IDs
        dao.getTraceIdsByThreadIds(projectId, threadIds, connection)
                .flatMap(traceIds -> {
                    if (traceIds.isEmpty()) {
                        log.info("No traces found for thread IDs, skipping deletion");
                        return Mono.empty();
                    }
                    log.info("Found '{}' traces for thread IDs, proceeding with deletion", traceIds.size());

                    return delete(traceIds, projectId, connection);
                })));
    }

    @Override
    public Mono<TraceThread> getThreadById(@NonNull UUID projectId, @NonNull String threadId) {
        return dao.findThreadById(projectId, threadId)
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Trace Thread", threadId))));
    }

    @Override
    public Flux<Trace> search(int limit, @NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMapMany(it -> dao.search(limit, it));
    }

    @Override
    public Mono<Long> countTraces(@NonNull Set<UUID> projectIds) {
        return dao.countTraces(projectIds);
    }

    @Override
    public Flux<TraceThread> threadsSearch(int limit, @NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMapMany(it -> dao.threadsSearch(limit, it));
    }

}
