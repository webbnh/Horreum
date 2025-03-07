import { useEffect, useMemo, useRef, useState } from "react"
import { useDispatch, useSelector } from "react-redux"
import {
    ActionGroup,
    Bullseye,
    Card,
    CardBody,
    EmptyState,
    EmptyStateBody,
    PageSection,
    Spinner,
} from "@patternfly/react-core"
import { expandable, ICell, IRow, Table, TableHeader, TableBody } from "@patternfly/react-table"
import { useHistory, NavLink } from "react-router-dom"
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, YAxis } from "recharts"

import {datasetApi, Test, testApi, View} from "../../api"
import { dispatchError } from "../../alerts"
import { tokenSelector } from "../../auth"
import { colors } from "../../charts"

import PrintButton from "../../components/PrintButton"
import FragmentTabs, { FragmentTab } from "../../components/FragmentTabs"

import { renderValue } from "./components"
import { fetchViews } from "../tests/actions"
import { viewsSelector } from "./selectors"

type Ds = {
    id: number
    runId: number
    ordinal: number
}

export default function DatasetComparison() {
    window.document.title = "Dataset comparison: Horreum"
    const history = useHistory()
    const params = new URLSearchParams(history.location.search)
    const testId = parseInt(params.get("testId") || "-1")
    const views = useSelector(viewsSelector(testId))
    const dispatch = useDispatch()
    const [test, setTest] = useState<Test>()
    useEffect(() => {
        testApi.get(testId).then(
            test => {
                setTest(test)
                dispatch(fetchViews(testId))
            },
            e => dispatchError(dispatch, e, "FETCH_TEST", "Failed to fetch test " + testId)
        )
    }, [testId])
    const datasets = useMemo(
        () =>
            params
                .getAll("ds")
                .map(ds => {
                    const parts = ds.split("_")
                    return {
                        id: parseInt(parts[0]),
                        runId: parseInt(parts[1]),
                        ordinal: parseInt(parts[2]),
                    }
                })
                .sort((a, b) => (a.runId - b.runId) * 1000 + (a.ordinal - b.ordinal)),
        []
    )
    const headers = useMemo(
        () => [
            { title: "Name", cellFormatters: [expandable] },
            ...datasets.map(item => ({
                title: (
                    <NavLink to={`/run/${item.runId}#dataset${item.ordinal}`}>
                        {item.runId}/{item.ordinal + 1}
                    </NavLink>
                ),
            })),
        ],
        [datasets]
    )

    const defaultView = views?.find(v => (v.name = "Default"))

    return (
        <PageSection>
            <Card>
                <CardBody>
                    {headers.length <= 1 ? (
                        <EmptyState>
                            <EmptyStateBody>No datasets have been loaded</EmptyStateBody>
                        </EmptyState>
                    ) : (
                        <FragmentTabs>
                            <FragmentTab title="Labels" fragment="labels">
                                <LabelsComparison headers={headers} datasets={datasets} />
                            </FragmentTab>
                            <FragmentTab title="Default view" fragment="view_default" isHidden={!test}>
                                {defaultView ? (
                                    <ViewComparison headers={headers} view={defaultView} datasets={datasets} />
                                ) : (
                                    <Bullseye>
                                        <Spinner size="xl" />
                                    </Bullseye>
                                )}
                            </FragmentTab>
                        </FragmentTabs>
                    )}
                </CardBody>
            </Card>
        </PageSection>
    )
}

type LabelsComparisonProps = {
    headers: ICell[]
    datasets: Ds[]
}

function LabelsComparison(props: LabelsComparisonProps) {
    const [loading, setLoading] = useState(false)
    const [rows, setRows] = useState<IRow[]>([])

    const dispatch = useDispatch()
    useEffect(() => {
        setLoading(true)
        Promise.all(props.datasets.map(ds => datasetApi.labelValues(ds.id).then(values => ({ ...ds, values }))))
            .then(
                labels => {
                    const rows: any[][] = []
                    labels.forEach((item, index) => {
                        item.values.forEach(label => {
                            let row = rows.find(r => r[0] === label.name)
                            if (row === undefined) {
                                row = [label.name, ...labels.map(x => (x.id === item.id ? label.value : undefined))]
                                rows.push(row)
                            } else {
                                row[index + 1] = label.value
                            }
                        })
                    })
                    rows.sort((r1, r2) => r1[0].localeCompare(r2[0]))
                    const renderRows: IRow[] = []
                    rows.forEach(row => {
                        const numeric = row.every((value, i) => i === 0 || typeof value === "number")
                        renderRows.push({
                            isOpen: numeric ? false : undefined,
                            cells: row.map(value => ({
                                title: typeof value === "object" ? JSON.stringify(value) : value,
                            })),
                        })
                        if (numeric) {
                            renderRows.push({
                                parent: renderRows.length - 1,
                                cells: [
                                    "",
                                    {
                                        title: (
                                            <BarValuesChart
                                                values={row.slice(1)}
                                                legend={labels.map(item => `${item.runId}/${item.ordinal + 1}`)}
                                            />
                                        ),
                                        props: {
                                            colSpan: labels.length,
                                        },
                                    },
                                ],
                            })
                        }
                    })
                    setRows(renderRows)
                },
                e => dispatchError(dispatch, e, "LOAD_LABELS", "Failed to load labels for one of the datasets.")
            )
            .finally(() => setLoading(false))
    }, [props.datasets])
    const componentRef = useRef<HTMLDivElement>(null)
    if (loading) {
        return (
            <Bullseye>
                <Spinner size="xl" />
            </Bullseye>
        )
    }
    return (
        <>
            <ActionGroup>
                <PrintButton printRef={componentRef} />
            </ActionGroup>
            <div ref={componentRef}>
                <Table
                    aria-label="Label comparison"
                    variant="compact"
                    cells={props.headers}
                    rows={rows}
                    isExpandable={true}
                    onCollapse={(_, rowIndex, isOpen) => {
                        rows[rowIndex].isOpen = isOpen
                        setRows([...rows])
                    }}
                >
                    <TableHeader />
                    <TableBody />
                </Table>
            </div>
        </>
    )
}

type ViewComparisonProps = {
    headers: ICell[]
    view: View
    datasets: Ds[]
}

function ViewComparison(props: ViewComparisonProps) {
    const dispatch = useDispatch()
    const [loading, setLoading] = useState(false)
    const [rows, setRows] = useState<IRow[]>()
    const token = useSelector(tokenSelector)
    useEffect(() => {
        setLoading(true)
        Promise.all(props.datasets.map(ds => datasetApi.getSummary(ds.id, props.view.id)))
            .then(
                summaries => {
                    setRows(
                        props.view.components.map(vc => ({
                            cells: [
                                vc.headerName,
                                ...summaries.map(summary => {
                                    const render = renderValue(
                                        vc.render,
                                        vc.labels.length == 1 ? vc.labels[0] : undefined,
                                        token
                                    )
                                    return render(summary.view?.[vc.id], summary)
                                }),
                            ],
                        }))
                    )
                },
                e => dispatchError(dispatch, e, "FETCH_VIEW", "Failed to fetch view for one of datasets.")
            )
            .finally(() => setLoading(false))
    }, [props.datasets, props.view])
    const componentRef = useRef<HTMLDivElement>(null)
    if (loading) {
        return (
            <Bullseye>
                <Spinner size="xl" />
            </Bullseye>
        )
    }
    return (
        <>
            <ActionGroup>
                <PrintButton printRef={componentRef} />
            </ActionGroup>
            <div ref={componentRef}>
                <Table aria-label="View comparison" variant="compact" cells={props.headers} rows={rows}>
                    <TableHeader />
                    <TableBody />
                </Table>
            </div>
        </>
    )
}

type BarValuesChartProps = {
    values: number[]
    legend: string[]
}

function BarValuesChart(props: BarValuesChartProps) {
    const data: Record<string, number>[] = [{}]
    props.values.forEach((v, i) => {
        data[0][i] = v
    })

    return (
        <ResponsiveContainer width="100%" height={250}>
            <BarChart data={data} style={{ userSelect: "none" }}>
                <CartesianGrid key="grid" strokeDasharray="3 3" />,
                <YAxis
                    key="yaxis"
                    yAxisId={0}
                    tick={{ fontSize: 12 }}
                    tickFormatter={value => value.toLocaleString(undefined, { maximumFractionDigits: 2 })}
                />
                ,
                {props.legend.map((name, i) => (
                    <Bar
                        key={i}
                        dataKey={i}
                        maxBarSize={80}
                        fill={colors[i % colors.length]}
                        isAnimationActive={false}
                        label={({ x, width, y, stroke, value }) => {
                            return (
                                <text x={x + width / 2} y={y} dy={-4} fill={stroke} fontSize={16} textAnchor="middle">
                                    {name}: {value}
                                </text>
                            )
                        }}
                    />
                ))}
            </BarChart>
        </ResponsiveContainer>
    )
}
