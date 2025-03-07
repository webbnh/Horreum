import * as actionTypes from "./actionTypes"
import { Map } from "immutable"
import * as utils from "../../utils"
import { Team } from "../../components/TeamSelect"
import { ThunkDispatch } from "redux-thunk"
import { AddAlertAction } from "../../alerts"
import { RunExtended, RunSummary, Access } from "../../api"

export interface RunSchemas {
    // schemaid -> uri
    [key: string]: string
}

export class RunsState {
    loading = false
    byId?: Map<number, RunExtended> = undefined
    byTest?: Map<number, Map<number, RunExtended>> = undefined
    currentPage: number[] = []
    currentTotal = 0
    selectedRoles?: Team = undefined
    suggestQuery: string[] = []
    suggestions: string[] = []
}

export interface LoadingAction {
    type: typeof actionTypes.LOADING
}

export interface LoadedAction {
    type: typeof actionTypes.LOADED
    run?: RunExtended
    total?: number
}

export interface TestIdAction {
    type: typeof actionTypes.TESTID
    id: number
    runs: RunSummary[]
    total: number
}

export interface LoadSuggestionsAction {
    type: typeof actionTypes.LOAD_SUGGESTIONS
    query: string
}

export interface SuggestAction {
    type: typeof actionTypes.SUGGEST
    responseReceived: boolean
    options: string[]
}

export interface SelectRolesAction {
    type: typeof actionTypes.SELECT_ROLES
    selection: Team
}

export interface UpdateTokenAction {
    type: typeof actionTypes.UPDATE_TOKEN
    id: number
    testid: number
    token: string | null
}

export interface UpdateAccessAction {
    type: typeof actionTypes.UPDATE_ACCESS
    id: number
    testid: number
    owner: string
    access: Access
}

export interface TrashAction {
    type: typeof actionTypes.TRASH
    id: number
    testid: number
    isTrashed: boolean
}

export interface UpdateDescriptionAction {
    type: typeof actionTypes.UPDATE_DESCRIPTION
    id: number
    testid: number
    description: string
}

export interface UpdateSchemaAction {
    type: typeof actionTypes.UPDATE_SCHEMA
    id: number
    testid: number
    path: string | undefined
    schema: string
    schemas: RunSchemas
}

export interface UpdateDatasetsAction {
    type: typeof actionTypes.UPDATE_DATASETS
    id: number
    testid: number
    datasets: number[]
}

type RunsAction =
    | LoadingAction
    | LoadedAction
    | TestIdAction
    | LoadSuggestionsAction
    | SuggestAction
    | SelectRolesAction
    | UpdateTokenAction
    | UpdateAccessAction
    | TrashAction
    | UpdateDescriptionAction
    | UpdateSchemaAction
    | UpdateDatasetsAction

export type RunsDispatch = ThunkDispatch<any, unknown, RunsAction | AddAlertAction>

//Takes events and updates the state accordingly
export const reducer = (state = new RunsState(), action: RunsAction) => {
    switch (action.type) {
        case actionTypes.LOADING:
            state.loading = true
            break
        case actionTypes.LOADED: {
            state.loading = false
            if (!state.byId) {
                state.byId = Map<number, RunExtended>()
            }
            if (action.run !== undefined) {
                const byId = state.byId as Map<number, RunExtended>
                state.byId = byId.set(action.run.id, {
                    ...(byId.get(action.run.id) || {}),
                    ...action.run,
                })
            }
            if (action.total) {
                state.currentTotal = action.total
            }
            break
        }
        case actionTypes.TESTID: {
            state.loading = false
            const byTest = state.byTest || Map<number, Map<number, RunExtended>>()
            let testMap: Map<number, RunExtended> = byTest.get(action.id, Map<number, RunExtended>())
            if (!utils.isEmpty(action.runs)) {
                action.runs.forEach(run => {
                    if (run !== undefined) {
                        testMap = testMap.set(run.id, {
                            ...testMap.get(run.id),
                            data: "",
                            schemas: [],
                            ...run,
                        })
                    }
                })
            }
            state.byTest = byTest.set(action.id, testMap)
            state.currentPage = action.runs.map(run => run.id)
            state.currentTotal = action.total
            break
        }
        case actionTypes.LOAD_SUGGESTIONS: {
            if (state.suggestQuery.length === 0) {
                state.suggestQuery = [action.query]
            } else {
                state.suggestQuery = [state.suggestQuery[0], action.query]
            }
            break
        }
        case actionTypes.SUGGEST: {
            state.suggestions = action.options
            if (action.responseReceived) {
                state.suggestQuery.shift()
            }
            break
        }
        case actionTypes.SELECT_ROLES: {
            state.selectedRoles = action.selection
            break
        }
        case actionTypes.UPDATE_TOKEN: {
            state = updateRun(state, action.id, action.testid, { token: action.token })
            break
        }
        case actionTypes.UPDATE_ACCESS: {
            state = updateRun(state, action.id, action.testid, { owner: action.owner, access: action.access })
            break
        }
        case actionTypes.TRASH: {
            state = updateRun(state, action.id, action.testid, { trashed: action.isTrashed })
            break
        }
        case actionTypes.UPDATE_DESCRIPTION: {
            state = updateRun(state, action.id, action.testid, { description: action.description })
            break
        }
        case actionTypes.UPDATE_SCHEMA: {
            state = updateRun(state, action.id, action.testid, run => {
                const copy = { ...run, schema: action.schemas }
                copy.schema = action.schemas
                return copy
            })
            break
        }
        case actionTypes.UPDATE_DATASETS: {
            state = updateRun(state, action.id, action.testid, { datasets: action.datasets })
            break
        }
        default:
    }
    return state
}

function updateRun(
    state: RunsState,
    id: number,
    testid: number,
    patch: Record<string, unknown> | ((current: RunExtended) => RunExtended)
) {
    const run = state.byId?.get(id)
    if (run) {
        const updated = typeof patch === "function" ? patch(run) : { ...run, ...patch }
        state.byId = (state.byId || Map<number, RunExtended>()).set(run.id, updated)
    }
    let testMap: Map<number, RunExtended> | undefined = state.byTest?.get(testid)
    if (testMap) {
        const current = testMap.get(id)
        if (current) {
            const updated = typeof patch === "function" ? patch(current) : { ...current, ...patch }
            testMap = testMap.set(id, updated)
        }
        state.byTest = state.byTest?.set(testid, testMap)
    }
    return state
}
