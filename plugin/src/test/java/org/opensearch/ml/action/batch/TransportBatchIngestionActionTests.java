/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.opensearch.ml.common.MLTask.ERROR_FIELD;
import static org.opensearch.ml.common.MLTask.STATE_FIELD;
import static org.opensearch.ml.common.MLTaskState.COMPLETED;
import static org.opensearch.ml.common.MLTaskState.FAILED;
import static org.opensearch.ml.engine.ingest.AbstractIngestion.INGEST_FIELDS;
import static org.opensearch.ml.engine.ingest.AbstractIngestion.INPUT_FIELD_NAMES;
import static org.opensearch.ml.engine.ingest.AbstractIngestion.OUTPUT_FIELD_NAMES;
import static org.opensearch.ml.engine.ingest.S3DataIngestion.SOURCE;
import static org.opensearch.ml.task.MLTaskManager.TASK_SEMAPHORE_TIMEOUT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.client.Client;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.Index;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.ml.common.MLTask;
import org.opensearch.ml.common.transport.batch.MLBatchIngestionInput;
import org.opensearch.ml.common.transport.batch.MLBatchIngestionRequest;
import org.opensearch.ml.common.transport.batch.MLBatchIngestionResponse;
import org.opensearch.ml.task.MLTaskManager;
import org.opensearch.tasks.Task;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

public class TransportBatchIngestionActionTests extends OpenSearchTestCase {
    @Mock
    private Client client;
    @Mock
    private TransportService transportService;
    @Mock
    private MLTaskManager mlTaskManager;
    @Mock
    private ActionFilters actionFilters;
    @Mock
    private MLBatchIngestionRequest mlBatchIngestionRequest;
    @Mock
    private Task task;
    @Mock
    ActionListener<MLBatchIngestionResponse> actionListener;
    @Mock
    ThreadPool threadPool;

    private TransportBatchIngestionAction batchAction;
    private MLBatchIngestionInput batchInput;

    @Before
    public void setup() {
        MockitoAnnotations.openMocks(this);
        batchAction = new TransportBatchIngestionAction(transportService, actionFilters, client, mlTaskManager, threadPool);

        Map<String, Object> fieldMap = new HashMap<>();
        fieldMap.put("input", "$.content");
        fieldMap.put("output", "$.SageMakerOutput");
        fieldMap.put(INPUT_FIELD_NAMES, Arrays.asList("chapter", "title"));
        fieldMap.put(OUTPUT_FIELD_NAMES, Arrays.asList("chapter_embedding", "title_embedding"));
        fieldMap.put(INGEST_FIELDS, Arrays.asList("$.id"));

        Map<String, String> credential = Map
            .of("region", "us-east-1", "access_key", "some accesskey", "secret_key", "some secret", "session_token", "some token");
        Map<String, Object> dataSource = new HashMap<>();
        dataSource.put("type", "s3");
        dataSource.put(SOURCE, Arrays.asList("s3://offlinebatch/output/sagemaker_djl_batch_input.json.out"));

        batchInput = MLBatchIngestionInput
            .builder()
            .indexName("testIndex")
            .fieldMapping(fieldMap)
            .credential(credential)
            .dataSources(dataSource)
            .build();
        when(mlBatchIngestionRequest.getMlBatchIngestionInput()).thenReturn(batchInput);
    }

    public void test_doExecute_success() {
        doAnswer(invocation -> {
            ActionListener<IndexResponse> listener = invocation.getArgument(1);
            ShardId shardId = new ShardId(new Index("indexName", "uuid"), 1);
            IndexResponse indexResponse = new IndexResponse(shardId, "taskId", 1, 1, 1, true);
            listener.onResponse(indexResponse);
            return null;
        }).when(mlTaskManager).createMLTask(isA(MLTask.class), isA(ActionListener.class));
        batchAction.doExecute(task, mlBatchIngestionRequest, actionListener);

        verify(actionListener).onResponse(any(MLBatchIngestionResponse.class));
    }

    public void test_doExecute_handleSuccessRate100() {
        batchAction.handleSuccessRate(100, "taskid");
        verify(mlTaskManager).updateMLTask("taskid", Map.of(STATE_FIELD, COMPLETED), 5000, true);
    }

    public void test_doExecute_handleSuccessRate50() {
        batchAction.handleSuccessRate(50, "taskid");
        verify(mlTaskManager)
            .updateMLTask(
                "taskid",
                Map.of(STATE_FIELD, FAILED, ERROR_FIELD, "batch ingestion successful rate is 50.0"),
                TASK_SEMAPHORE_TIMEOUT,
                true
            );
    }

    public void test_doExecute_handleSuccessRate0() {
        batchAction.handleSuccessRate(0, "taskid");
        verify(mlTaskManager)
            .updateMLTask(
                "taskid",
                Map.of(STATE_FIELD, FAILED, ERROR_FIELD, "batch ingestion successful rate is 0"),
                TASK_SEMAPHORE_TIMEOUT,
                true
            );
    }

    public void test_doExecute_noDataSource() {
        MLBatchIngestionInput batchInput = MLBatchIngestionInput
            .builder()
            .indexName("testIndex")
            .fieldMapping(new HashMap<>())
            .credential(new HashMap<>())
            .dataSources(new HashMap<>())
            .build();
        when(mlBatchIngestionRequest.getMlBatchIngestionInput()).thenReturn(batchInput);
        batchAction.doExecute(task, mlBatchIngestionRequest, actionListener);

        ArgumentCaptor<OpenSearchStatusException> argumentCaptor = ArgumentCaptor.forClass(OpenSearchStatusException.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals(
            "IllegalArgumentException in the batch ingestion input: The batch ingest input data source cannot be null",
            argumentCaptor.getValue().getMessage()
        );
    }

    public void test_doExecute_noTypeInDataSource() {
        MLBatchIngestionInput batchInput = MLBatchIngestionInput
            .builder()
            .indexName("testIndex")
            .fieldMapping(new HashMap<>())
            .credential(new HashMap<>())
            .dataSources(Map.of("source", "some url"))
            .build();
        when(mlBatchIngestionRequest.getMlBatchIngestionInput()).thenReturn(batchInput);
        batchAction.doExecute(task, mlBatchIngestionRequest, actionListener);

        ArgumentCaptor<OpenSearchStatusException> argumentCaptor = ArgumentCaptor.forClass(OpenSearchStatusException.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals(
            "IllegalArgumentException in the batch ingestion input: The batch ingest input data source is missing data type or source",
            argumentCaptor.getValue().getMessage()
        );
    }

    public void test_doExecute_invalidS3DataSource() {
        Map<String, Object> dataSource = new HashMap<>();
        dataSource.put("type", "s3");
        dataSource.put(SOURCE, Arrays.asList("s3://offlinebatch/output/sagemaker_djl_batch_input.json.out", "invalid s3"));

        MLBatchIngestionInput batchInput = MLBatchIngestionInput
            .builder()
            .indexName("testIndex")
            .fieldMapping(new HashMap<>())
            .credential(new HashMap<>())
            .dataSources(dataSource)
            .build();
        when(mlBatchIngestionRequest.getMlBatchIngestionInput()).thenReturn(batchInput);
        batchAction.doExecute(task, mlBatchIngestionRequest, actionListener);

        ArgumentCaptor<OpenSearchStatusException> argumentCaptor = ArgumentCaptor.forClass(OpenSearchStatusException.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals(
            "IllegalArgumentException in the batch ingestion input: The following batch ingest input S3 URIs are invalid: [invalid s3]",
            argumentCaptor.getValue().getMessage()
        );
    }

    public void test_doExecute_emptyS3DataSource() {
        Map<String, Object> dataSource = new HashMap<>();
        dataSource.put("type", "s3");
        dataSource.put(SOURCE, new ArrayList<>());

        MLBatchIngestionInput batchInput = MLBatchIngestionInput
            .builder()
            .indexName("testIndex")
            .fieldMapping(new HashMap<>())
            .credential(new HashMap<>())
            .dataSources(dataSource)
            .build();
        when(mlBatchIngestionRequest.getMlBatchIngestionInput()).thenReturn(batchInput);
        batchAction.doExecute(task, mlBatchIngestionRequest, actionListener);

        ArgumentCaptor<OpenSearchStatusException> argumentCaptor = ArgumentCaptor.forClass(OpenSearchStatusException.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals(
            "IllegalArgumentException in the batch ingestion input: The batch ingest input s3Uris is empty",
            argumentCaptor.getValue().getMessage()
        );
    }

    public void test_doExecute_mlTaskCreateException() {
        doAnswer(invocation -> {
            ActionListener<IndexResponse> listener = invocation.getArgument(1);
            listener.onFailure(new RuntimeException("Failed to create ML Task"));
            return null;
        }).when(mlTaskManager).createMLTask(isA(MLTask.class), isA(ActionListener.class));
        batchAction.doExecute(task, mlBatchIngestionRequest, actionListener);

        ArgumentCaptor<RuntimeException> argumentCaptor = ArgumentCaptor.forClass(RuntimeException.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("Failed to create ML Task", argumentCaptor.getValue().getMessage());
    }

    public void test_doExecute_batchIngestionFailed() {
        doAnswer(invocation -> {
            ActionListener<IndexResponse> listener = invocation.getArgument(1);
            ShardId shardId = new ShardId(new Index("indexName", "uuid"), 1);
            IndexResponse indexResponse = new IndexResponse(shardId, "taskId", 1, 1, 1, true);
            listener.onResponse(indexResponse);
            return null;
        }).when(mlTaskManager).createMLTask(isA(MLTask.class), isA(ActionListener.class));

        doThrow(new OpenSearchStatusException("some error", RestStatus.INTERNAL_SERVER_ERROR)).when(mlTaskManager).add(isA(MLTask.class));
        batchAction.doExecute(task, mlBatchIngestionRequest, actionListener);

        ArgumentCaptor<OpenSearchStatusException> argumentCaptor = ArgumentCaptor.forClass(OpenSearchStatusException.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("some error", argumentCaptor.getValue().getMessage());
        verify(mlTaskManager).updateMLTask("taskId", Map.of(STATE_FIELD, FAILED, ERROR_FIELD, "some error"), TASK_SEMAPHORE_TIMEOUT, true);
    }
}
