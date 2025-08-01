/**
 * This file was auto-generated by Fern from our API Definition.
 */

import * as serializers from "../index";
import * as OpikApi from "../../api/index";
import * as core from "../../core";
import { TraceThreadFilterOperator } from "./TraceThreadFilterOperator";

export const TraceThreadFilter: core.serialization.ObjectSchema<
    serializers.TraceThreadFilter.Raw,
    OpikApi.TraceThreadFilter
> = core.serialization.object({
    field: core.serialization.string().optional(),
    operator: TraceThreadFilterOperator.optional(),
    key: core.serialization.string().optional(),
    value: core.serialization.string().optional(),
});

export declare namespace TraceThreadFilter {
    export interface Raw {
        field?: string | null;
        operator?: TraceThreadFilterOperator.Raw | null;
        key?: string | null;
        value?: string | null;
    }
}
