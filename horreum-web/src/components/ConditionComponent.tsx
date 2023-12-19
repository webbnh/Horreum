import { Alert, FormGroup, Switch } from "@patternfly/react-core"
import { ConditionComponent as ConditionComponentDef } from "../api"
import LogSlider from "./LogSlider"
import EnumSelect from "./EnumSelect"
import NumberBound from "./NumberBound"

type ConditionComponentProps = {
    value: any
    onChange(value: any): void
    isTester: boolean

} & ConditionComponentDef

export default function ConditionComponent({ value, onChange, properties, isTester, type, title, name, description }: ConditionComponentProps) {
    let component
    switch (type) {
        case "LOG_SLIDER":
            component = (
                <LogSlider
                    value={value * ((properties as any).scale || 1)}
                    onChange={value => {
                    const scale = (properties as any).scale
                    onChange(scale ? value / scale : value)
                    }}
                    isDisabled={!isTester}
                    isDiscrete={(properties as any).discrete}
                    min={(properties as any).min}
                    max={(properties as any).max}
                    unit={(properties as any).unit}
                />
            )
            break
        case "ENUM":
            component = (
                <EnumSelect
                    options={(properties as any).options}
                    selected={value}
                    onSelect={onChange}
                    isDisabled={!isTester}
                />
            )
            break
        case "NUMBER_BOUND":
            component = (
                <NumberBound
                    {...value}
                    onChange={(enabled: boolean, inclusive: boolean, value: number) => {
                        onChange({ enabled, inclusive, value })
                    }}
                    isDisabled={!isTester}
                />
            )
            break
        case "SWITCH":
            component = (
                <Switch
                    label="ON"
                    labelOff="OFF"
                    isDisabled={!isTester}
                    isChecked={value}
                    onChange={checked => onChange(checked)}
                />
            )
            break
        default:
            component = <Alert variant="danger" title={"Unsupported type " + type} />
            break
    }
    return (
        <FormGroup fieldId={name} key={name} label={title} helperText={description}>
            {component}
        </FormGroup>
    )
}
