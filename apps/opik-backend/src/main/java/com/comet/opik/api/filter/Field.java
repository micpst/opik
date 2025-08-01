package com.comet.opik.api.filter;

import com.comet.opik.domain.filter.FilterStrategy;
import com.fasterxml.jackson.annotation.JsonValue;

public interface Field {

    String ID_QUERY_PARAM = "id";
    String NAME_QUERY_PARAM = "name";
    String DESCRIPTION_QUERY_PARAM = "description";
    String START_TIME_QUERY_PARAM = "start_time";
    String END_TIME_QUERY_PARAM = "end_time";
    String INPUT_QUERY_PARAM = "input";
    String OUTPUT_QUERY_PARAM = "output";
    String METADATA_QUERY_PARAM = "metadata";
    String MODEL_QUERY_PARAM = "model";
    String PROVIDER_QUERY_PARAM = "provider";
    String TOTAL_ESTIMATED_COST_QUERY_PARAM = "total_estimated_cost";
    String TAGS_QUERY_PARAM = "tags";
    String USAGE_COMPLETION_TOKENS_QUERY_PARAM = "usage.completion_tokens";
    String USAGE_PROMPT_TOKENS_QUERY_PARAM = "usage.prompt_tokens";
    String USAGE_TOTAL_TOKEN_QUERY_PARAMS = "usage.total_tokens";
    String FEEDBACK_SCORES_QUERY_PARAM = "feedback_scores";
    String DURATION_QUERY_PARAM = "duration";
    String THREAD_ID_QUERY_PARAM = "thread_id";
    String DATASET_ID_QUERY_PARAM = "dataset_id";
    String PROMPT_IDS_QUERY_PARAM = "prompt_ids";
    String NUMBER_OF_MESSAGES_QUERY_PARAM = "number_of_messages";
    String FIRST_MESSAGE_QUERY_PARAM = "first_message";
    String LAST_MESSAGE_QUERY_PARAM = "last_message";
    String CREATED_AT_QUERY_PARAM = "created_at";
    String LAST_UPDATED_AT_QUERY_PARAM = "last_updated_at";
    String CREATED_BY_QUERY_PARAM = "created_by";
    String LAST_UPDATED_BY_QUERY_PARAM = "last_updated_by";
    String LAST_CREATED_EXPERIMENT_AT_QUERY_PARAM = "last_created_experiment_at";
    String LAST_CREATED_OPTIMIZATION_AT_QUERY_PARAM = "last_created_optimization_at";
    String GUARDRAILS_QUERY_PARAM = "guardrails";
    String VISIBILITY_MODE_QUERY_PARAM = "visibility_mode";
    String ERROR_INFO_QUERY_PARAM = "error_info";
    String STATUS_QUERY_PARAM = "status";
    String TYPE_QUERY_PARAM = "type";
    String LLM_SPAN_COUNT_QUERY_PARAM = "llm_span_count";
    String VERSION_COUNT_QUERY_PARAM = "version_count";

    @JsonValue
    String getQueryParamField();

    FieldType getType();

    default boolean isDynamic(FilterStrategy filterStrategy) {
        return false;
    }

}
